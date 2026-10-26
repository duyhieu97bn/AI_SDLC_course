"""Luồng đầy đủ với Core giả (httpx.MockTransport): kích hoạt -> heartbeat -> việc DISCOVERY -> bị thu hồi (401)."""

import asyncio
import base64
import json
from pathlib import Path
from typing import Any

import httpx
import pytest

from site_connector import state
from site_connector.agent import EXIT_CONFIG, EXIT_REVOKED, Agent
from site_connector.api import CoreClient
from site_connector.config import ConnectorConfig


class FakeCore:
    def __init__(self, activation_status: int = 200) -> None:
        self.activation_status = activation_status
        self.calls: list[tuple[str, Any]] = []
        self.heartbeats = 0

    def handler(self, request: httpx.Request) -> httpx.Response:
        path = request.url.path.removeprefix("/api/v1/connector-agent")
        body = json.loads(request.content) if request.content else None
        self.calls.append((path, body))
        if path == "/activate":
            if self.activation_status != 200:
                return httpx.Response(self.activation_status, json={"code": "ACTIVATION_EXPIRED"})
            return httpx.Response(
                200,
                json={
                    "connectorId": "c-1",
                    "secret": "sec",
                    "tunnel": {"url": "https://core/tunnel/", "user": "tu", "password": "tp"},
                },
            )
        expected = "Basic " + base64.b64encode(b"c-1:sec").decode()
        if request.headers.get("Authorization") != expected:
            return httpx.Response(401)
        if path == "/heartbeat":
            self.heartbeats += 1
            if self.heartbeats >= 3:
                return httpx.Response(401)  # Admin thu hồi bộ kết nối
            return httpx.Response(
                200,
                json={
                    "config": {
                        "configVersion": "1",
                        "socksPort": 21001,
                        "forwards": [{"port": 22001, "host": "site-camera", "targetPort": 8554}],
                    },
                    "tasks": [{"id": "t-1", "type": "DISCOVERY", "timeoutSeconds": 1}],
                },
            )
        if path == "/tasks/t-1/result":
            return httpx.Response(204)
        return httpx.Response(404)


def _config(tmp_path: Path, code: str = "CODE-1") -> ConnectorConfig:
    return ConnectorConfig(  # type: ignore[call-arg]
        _env_file=None,
        server="https://core",
        activation_code=code,
        state_dir=tmp_path,
        heartbeat_seconds=1.5,
        chisel_bin=str(tmp_path / "khong-co-chisel"),
    )


def _run(config: ConnectorConfig, core: FakeCore) -> int:
    async def scenario() -> int:
        client = CoreClient(config.server, True, transport=httpx.MockTransport(core.handler))
        return await Agent(config, client).run()

    return asyncio.run(scenario())


def test_full_flow_until_revoked(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr("site_connector.discovery.local_ipv4_addresses", lambda: [])
    core = FakeCore()
    config = _config(tmp_path)
    assert _run(config, core) == EXIT_REVOKED

    paths = [p for p, _ in core.calls]
    assert paths[0] == "/activate"
    assert core.calls[0][1]["code"] == "CODE-1"
    assert paths.count("/heartbeat") == 3
    assert "/tasks/t-1/result" in paths  # việc lặp lại ở heartbeat thứ 2 chỉ chạy một lần
    assert paths.count("/tasks/t-1/result") == 1
    beat = core.calls[1][1]
    assert set(beat) == {"version", "uptimeSeconds", "tunnelUp", "rxBytes", "txBytes", "rxKbps", "txKbps"}
    assert beat["tunnelUp"] is False

    stored = state.load(config.credentials_file)
    assert stored.credentials is None
    assert stored.revoked_code_sha256 == state.code_hash("CODE-1")
    # Chạy lại với mã cũ: không gọi Core, báo cần mã mới
    core2 = FakeCore()
    assert _run(config, core2) == EXIT_CONFIG
    assert core2.calls == []


def test_activation_rejected(tmp_path: Path) -> None:
    core = FakeCore(activation_status=410)
    assert _run(_config(tmp_path), core) == EXIT_CONFIG
    assert not (tmp_path / "credentials.json").exists()


def test_missing_code(tmp_path: Path) -> None:
    assert _run(_config(tmp_path, code=""), FakeCore()) == EXIT_CONFIG
