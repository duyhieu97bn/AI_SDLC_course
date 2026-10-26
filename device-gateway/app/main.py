import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Any

import httpx
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, PlainTextResponse
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest

from app import __version__, logging_config
from app.events.health_monitor import HealthMonitor
from app.events.manager import EventsConfig, SubscriptionManager
from app.events.publisher import EventSink, RabbitPublisher
from app.onvif.client import DeviceTarget, OnvifClient, SessionCache
from app.onvif.errors import DeviceError
from app.routes import GatewayState, router
from app.security import InternalTokenMiddleware
from app.settings import Settings, settings
from app.transport.tunnel_router import TunnelRouter

logging_config.configure("camhub-device-gateway", settings.log_level)
log = logging.getLogger("app.main")


def create_app(config: Settings | None = None, sink: EventSink | None = None) -> FastAPI:
    """Dựng ứng dụng. ``sink`` thay RabbitMQ (dùng trong test); mặc định đẩy vào ``config.rabbitmq_url``."""
    cfg = config or settings

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        http = httpx.AsyncClient(
            verify=cfg.device_tls_verify,
            trust_env=False,
            follow_redirects=False,
            limits=httpx.Limits(max_connections=200, max_keepalive_connections=50),
        )
        publisher: RabbitPublisher | None = None
        event_sink = sink
        if event_sink is None:
            publisher = RabbitPublisher(cfg.rabbitmq_url, cfg.rabbitmq_exchange)
            publisher.start()
            event_sink = publisher
        tunnels = TunnelRouter(http, verify=cfg.device_tls_verify)
        cache = SessionCache(cfg.session_cache_seconds)

        def client_factory(target: DeviceTarget) -> OnvifClient:
            return OnvifClient(
                target,
                tunnels.client(target.proxy),
                timeout=cfg.device_timeout_seconds,
                cache=cache,
                rewrite_xaddr=cfg.rewrite_xaddr,
            )

        manager = SubscriptionManager(
            client_factory,
            event_sink,
            EventsConfig(
                pull_timeout_seconds=cfg.events_pull_timeout_seconds,
                ttl_seconds=cfg.events_subscription_ttl_seconds,
                backoff_max_seconds=cfg.events_backoff_max_seconds,
            ),
        )
        monitor = HealthMonitor(
            tunnels,
            event_sink,
            timeout_seconds=cfg.monitor_timeout_seconds,
            concurrency=cfg.monitor_concurrency,
            failure_threshold=cfg.monitor_failure_threshold,
        )
        state = GatewayState(
            settings=cfg, router=tunnels, cache=cache, sink=event_sink, manager=manager, monitor=monitor
        )
        app.state.gw = state
        app.state.publisher = publisher
        try:
            yield
        finally:
            await state.monitor.close()
            await state.manager.close()
            if publisher is not None:
                await publisher.stop()
            await tunnels.aclose()
            await http.aclose()

    app = FastAPI(title="CamHub Device Gateway", version=__version__, lifespan=lifespan)
    app.add_middleware(InternalTokenMiddleware, token=cfg.internal_token)
    app.include_router(router)

    @app.exception_handler(DeviceError)
    async def device_error(request: Request, exc: DeviceError) -> JSONResponse:
        log.warning("Lỗi thiết bị %s %s: %s", request.method, request.url.path, exc)
        return JSONResponse({"code": str(exc.code), "detail": exc.detail}, status_code=424)

    @app.exception_handler(RequestValidationError)
    async def validation_error(request: Request, exc: RequestValidationError) -> JSONResponse:
        # Bỏ "input" khỏi lỗi để không trả lại mật khẩu thiết bị trong body
        errors: list[dict[str, Any]] = [
            {k: v for k, v in err.items() if k in ("loc", "msg", "type")} for err in exc.errors()
        ]
        return JSONResponse({"code": "VALIDATION_ERROR", "detail": errors}, status_code=422)

    @app.get("/health")
    def health(request: Request) -> dict[str, str]:
        result = {"status": "UP", "version": __version__}
        publisher: RabbitPublisher | None = getattr(request.app.state, "publisher", None)
        if publisher is not None:
            result["rabbitmq"] = "UP" if publisher.connected else "DOWN"
        return result

    @app.get("/metrics", response_class=PlainTextResponse)
    def metrics() -> PlainTextResponse:
        return PlainTextResponse(generate_latest(), media_type=CONTENT_TYPE_LATEST)

    return app


app = create_app()
