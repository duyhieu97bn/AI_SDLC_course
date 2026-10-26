"""Fixture dùng chung: bộ giả lập ONVIF chạy thật trên localhost và Gateway với sink trong bộ nhớ."""

from __future__ import annotations

import socket
import sys
import threading
import time
from collections.abc import Iterator
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import pytest
import uvicorn
from fastapi.testclient import TestClient

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "tools" / "onvif-sim"))

from app.events.publisher import MemorySink  # noqa: E402
from app.main import create_app as create_gateway  # noqa: E402
from app.settings import Settings  # noqa: E402
from onvif_sim.app import OnvifSimulator, create_app  # noqa: E402
from onvif_sim.config import SimConfig  # noqa: E402

TOKEN = "test-internal-token"
SIM_USER = "admin"
SIM_PASSWORD = "Sim@12345"


def free_udp_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.bind(("127.0.0.1", 0))
        return int(s.getsockname()[1])


@dataclass
class RunningSim:
    config: SimConfig
    simulator: OnvifSimulator
    server: uvicorn.Server
    thread: threading.Thread

    @property
    def base_url(self) -> str:
        return self.config.base_url

    @property
    def port(self) -> int:
        return self.config.http_port

    def device(self, password: str | None = None) -> dict[str, Any]:
        return {
            "host": "127.0.0.1",
            "port": self.port,
            "username": self.config.user,
            "password": self.config.password if password is None else password,
        }

    def stop(self) -> None:
        self.server.should_exit = True
        self.thread.join(timeout=5)


def start_sim(**overrides: Any) -> RunningSim:
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.bind(("127.0.0.1", 0))
    port = int(sock.getsockname()[1])
    params: dict[str, Any] = {
        "name": "Sim Test",
        "user": SIM_USER,
        "password": SIM_PASSWORD,
        "http_port": port,
        "advertise_host": "127.0.0.1",
        "discovery_port": 0,
        "discovery_bind": "127.0.0.1",
        "rtsp_main": "rtsp://127.0.0.1:8554/sim-main",
        "rtsp_sub": "rtsp://127.0.0.1:8554/sim-sub",
    }
    params.update(overrides)
    config = SimConfig(**params)
    app = create_app(config)
    server = uvicorn.Server(uvicorn.Config(app, log_level="warning", lifespan="on"))
    thread = threading.Thread(target=server.run, kwargs={"sockets": [sock]}, daemon=True)
    thread.start()
    deadline = time.monotonic() + 10
    while not server.started:
        if time.monotonic() > deadline or not thread.is_alive():
            raise RuntimeError("Bộ giả lập ONVIF không khởi động được")
        time.sleep(0.02)
    return RunningSim(config, app.state.sim, server, thread)


@pytest.fixture(scope="session")
def sim() -> Iterator[RunningSim]:
    """Camera PTZ có B-frame, đồng hồ lệch +1 giờ (kiểm tra bù giờ PasswordDigest), có WS-Discovery."""
    running = start_sim(
        name="CamHub Sim PTZ",
        manufacturer="CamHub",
        model="SIM-PTZ",
        mac="02:00:00:00:00:01",
        ptz=True,
        bframes=True,
        clock_offset_seconds=3600,
        discovery_port=free_udp_port(),
    )
    yield running
    running.stop()


@pytest.fixture(scope="session")
def sim_fixed() -> Iterator[RunningSim]:
    """Camera cố định: không PTZ, không B-frame, không Analytics/Media2, sự kiện dạng tns1:VideoSource/MotionAlarm."""
    running = start_sim(
        name="CamHub Sim Fixed",
        model="SIM-FIX",
        mac="02:00:00:00:00:02",
        ptz=False,
        bframes=False,
        motion_topic="alarm",
        zones=False,
    )
    yield running
    running.stop()


@dataclass
class Gateway:
    client: TestClient
    sink: MemorySink

    def post(self, path: str, body: Any) -> Any:
        return self.client.post(path, json=body, headers={"X-Internal-Token": TOKEN})

    def wait_messages(
        self, routing_key: str, count: int = 1, timeout: float = 8.0, camera_id: str | None = None
    ) -> list[dict[str, Any]]:
        deadline = time.monotonic() + timeout
        while True:
            found = [
                p
                for k, p in self.sink.messages
                if k == routing_key and (camera_id is None or p.get("cameraId") == camera_id)
            ]
            if len(found) >= count or time.monotonic() > deadline:
                return found
            time.sleep(0.05)


@pytest.fixture(scope="session")
def gateway(sim: RunningSim) -> Iterator[Gateway]:
    config = Settings(
        internal_token=TOKEN,
        device_timeout_seconds=5,
        discovery_port=sim.config.discovery_port,
        discovery_multicast=False,
        events_pull_timeout_seconds=1,
        events_subscription_ttl_seconds=4,
        events_backoff_max_seconds=1,
    )
    sink = MemorySink()
    with TestClient(create_gateway(config, sink=sink)) as client:
        yield Gateway(client, sink)
