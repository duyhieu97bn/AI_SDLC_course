"""Quản lý đăng ký PullPoint cho từng camera (SADD 3.9.3).

Mỗi camera có một tác vụ nền: tạo PullPointSubscription, gọi PullMessages liên tục, gia hạn (Renew)
trước khi hết hạn, kết nối lại với backoff khi mất thiết bị. Mỗi lần trạng thái chuyển động thay đổi
thì đẩy ``motion.detected``; mất/có lại thiết bị thì đẩy ``device.offline`` / ``device.online``.
Đăng ký chỉ nằm trong bộ nhớ: Core cần đăng ký lại (idempotent) khi Gateway khởi động lại.
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
import time
import uuid
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import UTC, datetime

from app.events.normalize import format_instant, normalize_motion
from app.events.publisher import EventSink, message_type
from app.metrics import ACTIVE_SUBSCRIPTIONS
from app.models import SubscriptionStatus
from app.onvif.client import DeviceTarget, OnvifClient, PullPoint
from app.onvif.errors import DeviceError, ErrorCode

log = logging.getLogger(__name__)

ClientFactory = Callable[[DeviceTarget], OnvifClient]
_CONNECTIVITY = (ErrorCode.UNREACHABLE, ErrorCode.AUTH_FAILED, ErrorCode.NOT_ONVIF)


@dataclass(frozen=True)
class EventsConfig:
    pull_timeout_seconds: int = 10
    ttl_seconds: int = 60
    backoff_max_seconds: float = 60.0


def _now() -> str:
    return format_instant(datetime.now(UTC))


@dataclass
class _Subscription:
    camera_id: str
    target: DeviceTarget
    status: str = "STARTING"
    since: str = field(default_factory=_now)
    last_error: str | None = None
    last_event_at: str | None = None
    motion: bool | None = None
    online: bool | None = None
    states: dict[str, bool] = field(default_factory=dict)
    task: asyncio.Task[None] | None = None

    def to_status(self) -> SubscriptionStatus:
        return SubscriptionStatus.model_validate(
            {
                "camera_id": self.camera_id,
                "host": self.target.host,
                "port": self.target.port,
                "status": self.status,
                "since": self.since,
                "last_error": self.last_error,
                "last_event_at": self.last_event_at,
                "motion": self.motion,
            }
        )


class SubscriptionManager:
    def __init__(self, client_factory: ClientFactory, sink: EventSink, config: EventsConfig) -> None:
        self._factory = client_factory
        self._sink = sink
        self._config = config
        self._subs: dict[str, _Subscription] = {}

    def statuses(self) -> list[SubscriptionStatus]:
        return [s.to_status() for s in self._subs.values()]

    def get(self, camera_id: str) -> SubscriptionStatus | None:
        sub = self._subs.get(camera_id)
        return sub.to_status() if sub else None

    async def start(self, camera_id: str, target: DeviceTarget) -> SubscriptionStatus:
        """Bắt đầu (hoặc khởi động lại với địa chỉ/tài khoản mới) đăng ký sự kiện cho camera."""
        await self.stop(camera_id)
        sub = _Subscription(camera_id=camera_id, target=target)
        self._subs[camera_id] = sub
        sub.task = asyncio.create_task(self._run(sub), name=f"events-{camera_id}")
        log.info("Bắt đầu nhận sự kiện camera %s (%s)", camera_id, target.netloc)
        return sub.to_status()

    async def stop(self, camera_id: str) -> bool:
        sub = self._subs.pop(camera_id, None)
        if sub is None:
            return False
        if sub.task is not None:
            sub.task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await sub.task
        sub.status = "STOPPED"
        self._update_gauge()
        log.info("Dừng nhận sự kiện camera %s", camera_id)
        return True

    async def close(self) -> None:
        for camera_id in list(self._subs):
            await self.stop(camera_id)

    # ---- vòng lặp mỗi camera --------------------------------------------------------------------------

    def _set_status(self, sub: _Subscription, status: str, error: str | None = None) -> None:
        if sub.status != status:
            sub.status = status
            sub.since = _now()
            log.info("Camera %s: trạng thái sự kiện %s%s", sub.camera_id, status, f" ({error})" if error else "")
        sub.last_error = error
        self._update_gauge()

    def _update_gauge(self) -> None:
        ACTIVE_SUBSCRIPTIONS.set(sum(1 for s in self._subs.values() if s.status == "ACTIVE"))

    def _emit(self, routing_key: str, payload: dict[str, object]) -> None:
        message = {"messageId": str(uuid.uuid4()), "type": message_type(routing_key), **payload}
        self._sink.publish(routing_key, message)

    def _mark_offline(self, sub: _Subscription, error: DeviceError) -> None:
        reason = f"{error.code}: {error.detail}"
        self._set_status(sub, "OFFLINE", reason)
        if sub.online is not False:
            sub.online = False
            self._emit(
                "device.offline",
                {"cameraId": sub.camera_id, "occurredAt": _now(), "reason": reason, "source": "EVENTS"},
            )

    def _mark_online(self, sub: _Subscription) -> None:
        self._set_status(sub, "ACTIVE")
        if sub.online is False:
            self._emit(
                "device.online",
                {"cameraId": sub.camera_id, "occurredAt": _now(), "reason": "RECONNECTED", "source": "EVENTS"},
            )
        sub.online = True

    async def _run(self, sub: _Subscription) -> None:
        backoff = 1.0
        pull_point: PullPoint | None = None
        client: OnvifClient | None = None
        try:
            while True:
                client = self._factory(sub.target)
                try:
                    await client.connect()
                    pull_point = await client.create_pull_point(self._config.ttl_seconds)
                except Exception as exc:
                    e = _as_device_error(exc, sub)
                    if e.code == ErrorCode.NOT_SUPPORTED:
                        self._set_status(sub, "UNSUPPORTED", e.detail)
                        return
                    self._mark_offline(sub, e)
                    await asyncio.sleep(backoff)
                    backoff = min(backoff * 2, self._config.backoff_max_seconds)
                    continue
                self._mark_online(sub)
                backoff = 1.0
                try:
                    await self._pull_loop(sub, client, pull_point)
                except Exception as exc:
                    e = _as_device_error(exc, sub)
                    if e.code in _CONNECTIVITY:
                        self._mark_offline(sub, e)
                        pull_point = None
                        await asyncio.sleep(backoff)
                        backoff = min(backoff * 2, self._config.backoff_max_seconds)
                    else:
                        # Đăng ký hết hạn hoặc thiết bị từ chối PullMessages: tạo đăng ký mới, thiết bị vẫn online
                        self._set_status(sub, "RECONNECTING", f"{e.code}: {e.detail}")
                        await self._safe_unsubscribe(client, pull_point)
                        pull_point = None
                        await asyncio.sleep(1.0)
        except asyncio.CancelledError:
            if client is not None and pull_point is not None:
                await self._safe_unsubscribe(client, pull_point)
            raise

    async def _pull_loop(self, sub: _Subscription, client: OnvifClient, pull_point: PullPoint) -> None:
        ttl = self._config.ttl_seconds
        renew_at = time.monotonic() + ttl / 2
        while True:
            if time.monotonic() >= renew_at:
                await client.renew(pull_point, ttl)
                renew_at = time.monotonic() + ttl / 2
            timeout = max(1, min(self._config.pull_timeout_seconds, int(ttl / 2)))
            for raw in await client.pull_messages(pull_point, timeout):
                event = normalize_motion(raw)
                if event is None:
                    continue
                previous = sub.states.get(event.source_key)
                sub.states[event.source_key] = event.state
                sub.last_event_at = event.occurred_at
                sub.motion = any(sub.states.values())
                # Trạng thái ban đầu "không chuyển động" không phải là thay đổi
                if previous == event.state or (previous is None and not event.state):
                    continue
                log.info("Camera %s: chuyển động=%s (%s)", sub.camera_id, event.state, event.topic)
                self._emit(
                    "motion.detected",
                    {
                        "cameraId": sub.camera_id,
                        "state": event.state,
                        "occurredAt": event.occurred_at,
                        "source": "DEVICE",
                        "topic": event.topic,
                    },
                )

    @staticmethod
    async def _safe_unsubscribe(client: OnvifClient, pull_point: PullPoint) -> None:
        try:
            await asyncio.wait_for(client.unsubscribe(pull_point), timeout=3)
        except (DeviceError, TimeoutError) as e:
            log.debug("Hủy đăng ký PullPoint thất bại: %s", e)


def _as_device_error(exc: Exception, sub: _Subscription) -> DeviceError:
    if isinstance(exc, DeviceError):
        return exc
    log.exception("Lỗi không mong đợi khi nhận sự kiện camera %s", sub.camera_id, exc_info=exc)
    return DeviceError(ErrorCode.DEVICE_ERROR, f"Lỗi nội bộ Gateway ({type(exc).__name__})")
