import argparse
import asyncio
import contextlib
import json
import logging
import os
import signal
import sys
import time

from pydantic import ValidationError

from site_connector import __version__
from site_connector.agent import EXIT_CONFIG, EXIT_OK, Agent
from site_connector.config import ConnectorConfig

# Heartbeat thành công gần nhất cũ hơn mức này thì --healthcheck báo lỗi (4 chu kỳ 30 giây)
HEALTH_MAX_AGE_SECONDS = 120


def _load_config(config_file: str | None) -> ConnectorConfig:
    if config_file:
        return ConnectorConfig(_env_file=config_file)  # type: ignore[call-arg]
    return ConnectorConfig()  # type: ignore[call-arg]  # đọc từ biến môi trường


def _healthcheck(config: ConnectorConfig) -> int:
    try:
        data = json.loads(config.health_file.read_text(encoding="utf-8"))
        age = time.time() - float(data["lastHeartbeat"])
    except (OSError, ValueError, KeyError, TypeError):
        print("Chưa có heartbeat thành công")
        return 1
    print(f"Heartbeat gần nhất {age:.0f} giây trước, đường hầm {'lên' if data.get('tunnelUp') else 'xuống'}")
    return 0 if age <= HEALTH_MAX_AGE_SECONDS else 1


async def _serve(config: ConnectorConfig) -> int:
    agent = Agent(config)
    runner = asyncio.create_task(agent.run())
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGTERM, signal.SIGINT):
        with contextlib.suppress(NotImplementedError, RuntimeError):  # Windows không hỗ trợ
            loop.add_signal_handler(sig, runner.cancel)
    try:
        return await runner
    except asyncio.CancelledError:
        logging.getLogger(__name__).info("Đã dừng theo yêu cầu")
        return EXIT_OK


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="camhub-connector", description="CamHub Site Connector")
    parser.add_argument("--version", action="version", version=__version__)
    parser.add_argument("--config", help="tệp cấu hình KEY=VALUE (mặc định /etc/camhub-connector/connector.env)")
    parser.add_argument("--check", action="store_true", help="chỉ kiểm tra cấu hình rồi thoát")
    parser.add_argument("--healthcheck", action="store_true", help="kiểm tra heartbeat gần nhất (cho Docker)")
    args = parser.parse_args(argv)
    try:
        config = _load_config(args.config)
    except ValidationError as e:
        print(f"Cấu hình không hợp lệ:\n{e}", file=sys.stderr)
        return EXIT_CONFIG
    if args.check:
        print(f"Cấu hình hợp lệ, máy chủ {config.server}")
        return EXIT_OK
    if args.healthcheck:
        return _healthcheck(config)
    logging.basicConfig(
        level=os.environ.get("CAMHUB_LOG_LEVEL", "INFO").upper(),
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
    # httpx ghi mỗi request ở INFO (heartbeat 30 giây) – chỉ giữ cảnh báo
    logging.getLogger("httpx").setLevel(logging.WARNING)
    if config.tls_insecure:
        logging.getLogger(__name__).warning(
            "CAMHUB_TLS_INSECURE=true: KHÔNG kiểm tra chứng chỉ máy chủ (chỉ dùng cho dev)"
        )
    return asyncio.run(_serve(config))


if __name__ == "__main__":
    sys.exit(main())
