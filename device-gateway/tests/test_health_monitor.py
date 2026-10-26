"""Theo dõi online/offline (US-04.1) qua ``PUT/GET /v1/monitor/targets``."""

from __future__ import annotations

import socket
import socketserver
import threading
import time
from collections.abc import Iterator
from typing import Any

import pytest

from tests.conftest import TOKEN, Gateway, RunningSim
from tests.test_api import closed_port


class _RtspHandler(socketserver.StreamRequestHandler):
    def handle(self) -> None:
        if self.rfile.readline().startswith(b"OPTIONS"):
            self.wfile.write(b"RTSP/1.0 401 Unauthorized\r\nCSeq: 1\r\n\r\n")


@pytest.fixture
def rtsp_port() -> Iterator[int]:
    server = socketserver.ThreadingTCPServer(("127.0.0.1", 0), _RtspHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    yield int(server.server_address[1])
    server.shutdown()
    server.server_close()


def _put(gateway: Gateway, targets: list[dict[str, Any]], interval: int = 1) -> Any:
    return gateway.client.put(
        "/v1/monitor/targets",
        json={"targets": targets, "intervalSeconds": interval},
        headers={"X-Internal-Token": TOKEN},
    )


def _events(gateway: Gateway, camera_id: str, routing_key: str) -> list[dict[str, Any]]:
    return [p for k, p in gateway.sink.messages if k == routing_key and p["cameraId"] == camera_id]


def _wait(predicate: Any, timeout: float = 6.0) -> None:
    deadline = time.monotonic() + timeout
    while not predicate():
        if time.monotonic() > deadline:
            raise AssertionError("Hết thời gian chờ")
        time.sleep(0.05)


def test_monitor_onvif_online_and_closed_port_offline_after_two_failures(
    gateway: Gateway, sim: RunningSim, rtsp_port: int
) -> None:
    dead = closed_port()
    res = _put(
        gateway,
        [
            {"cameraId": "mon-sim", "host": "127.0.0.1", "port": sim.port, "protocol": "ONVIF"},
            {"cameraId": "mon-dead", "host": "127.0.0.1", "port": dead, "protocol": "ONVIF"},
            {
                "cameraId": "mon-rtsp",
                "host": "127.0.0.1",
                "port": 80,
                "protocol": "RTSP",
                "rtspHost": "127.0.0.1",
                "rtspPort": rtsp_port,
            },
        ],
    )
    assert res.status_code == 200, res.text
    assert {t["status"] for t in res.json()} == {"UNKNOWN"}

    # Lần kiểm tra đầu tiên luôn phát trạng thái hiện tại
    _wait(lambda: _events(gateway, "mon-sim", "device.online") and _events(gateway, "mon-rtsp", "device.online"))
    online = _events(gateway, "mon-sim", "device.online")[0]
    assert online["source"] == "MONITOR" and online["messageId"] and online["occurredAt"]

    # Sau lần thất bại thứ nhất vẫn UNKNOWN; OFFLINE chỉ sau 2 lần liên tiếp
    _wait(lambda: _status(gateway, "mon-dead")["lastCheckAt"] is not None)
    first = _status(gateway, "mon-dead")
    if not _events(gateway, "mon-dead", "device.offline"):
        assert first["status"] == "UNKNOWN"
    _wait(lambda: _events(gateway, "mon-dead", "device.offline"))
    offline = _events(gateway, "mon-dead", "device.offline")
    assert len(offline) == 1 and offline[0]["reason"].startswith("UNREACHABLE")
    assert _status(gateway, "mon-dead")["status"] == "OFFLINE"

    # Trạng thái không đổi thì không phát lại
    time.sleep(1.5)
    assert len(_events(gateway, "mon-sim", "device.online")) == 1
    assert len(_events(gateway, "mon-dead", "device.offline")) == 1

    # Gửi lại cùng danh sách (Core khởi động lại): phát lại trạng thái hiện tại
    _put(gateway, [{"cameraId": "mon-sim", "host": "127.0.0.1", "port": sim.port}])
    _wait(lambda: len(_events(gateway, "mon-sim", "device.online")) == 2)
    assert [t["cameraId"] for t in gateway.client.get("/v1/monitor/targets", headers=_auth()).json()] == ["mon-sim"]
    _put(gateway, [])


def test_monitor_rejects_duplicate_camera(gateway: Gateway) -> None:
    target = {"cameraId": "dup", "host": "127.0.0.1", "port": 80}
    res = _put(gateway, [target, target])
    assert res.status_code == 422
    assert res.json()["code"] == "DUPLICATE_CAMERA"


def test_rtsp_probe_rejects_non_rtsp_service(gateway: Gateway, sim: RunningSim) -> None:
    # Cổng HTTP của bộ giả lập trả "HTTP/1.1 400", không phải RTSP
    _put(
        gateway,
        [
            {
                "cameraId": "mon-http",
                "host": "127.0.0.1",
                "protocol": "RTSP",
                "rtspHost": "127.0.0.1",
                "rtspPort": sim.port,
            }
        ],
    )
    _wait(lambda: _events(gateway, "mon-http", "device.offline"))
    assert "RTSP" in _events(gateway, "mon-http", "device.offline")[0]["reason"]
    _put(gateway, [])


def _auth() -> dict[str, str]:
    return {"X-Internal-Token": TOKEN}


def _status(gateway: Gateway, camera_id: str) -> dict[str, Any]:
    items = gateway.client.get("/v1/monitor/targets", headers=_auth()).json()
    return next(t for t in items if t["cameraId"] == camera_id)


def test_closed_port_helper_is_closed() -> None:
    port = closed_port()
    with socket.socket() as s:
        s.settimeout(0.5)
        assert s.connect_ex(("127.0.0.1", port)) != 0
