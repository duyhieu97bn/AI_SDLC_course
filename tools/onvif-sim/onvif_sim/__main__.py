"""Chạy bộ giả lập: ``python -m onvif_sim`` (cấu hình bằng biến môi trường SIM_*)."""

import logging

import uvicorn

from onvif_sim.app import create_app
from onvif_sim.config import SimConfig


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    config = SimConfig.from_env()
    uvicorn.run(create_app(config), host="0.0.0.0", port=config.http_port, log_level="info")  # noqa: S104


if __name__ == "__main__":
    main()
