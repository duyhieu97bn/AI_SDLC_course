"""Gọi thiết bị qua tunnel site SOCKS5 (TS-06.3): kiểm tra proxy, cache client và đi trọn đường qua proxy."""

from __future__ import annotations

import time
from collections.abc import Iterator
from typing import Any

import httpx
import pytest

from app.models import DeviceAddress, MonitorTarget
from app.onvif.client import DeviceTarget, OnvifClient, SessionCache
from app.transport.tunnel_router import SocksError, TunnelRouter, normalize_proxy
from tests.conftest import TOKEN, Gateway, RunningSim
from tests.socks5_server import Socks5Server
from tests.test_api import closed_port

# Tên chỉ proxy biết (như IP LAN của site): Gateway không tự phân giải được
SITE_HOST = "camera-sim.site-lan"


@pytest.fixture
def socks() -> Iterator[Socks5Server]:
    server = Socks5Server({SITE_HOST: "127.0.0.1"}).start()
    yield server
    server.stop()


# ---- kiểm tra URL proxy ---------------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        (None, None),
        ("", None),
        ("   ", None),
        ("socks5://tunnel:21001", "socks5://tunnel:21001"),
        ("SOCKS5H://Tunnel:21001/", "socks5h://tunnel:21001"),
        ("socks5://10.0.0.5:1080", "socks5://10.0.0.5:1080"),
        ("socks5://[fd00::1]:1080", "socks5://[fd00::1]:1080"),
    ],
)
def test_normalize_proxy_accepts_socks5(raw: str | None, expected: str | None) -> None:
    assert normalize_proxy(raw) == expected


@pytest.mark.parametrize(
    "raw",
    [
        "http://tunnel:21001",
        "socks4://tunnel:21001",
        "tunnel:21001",
        "socks5://tunnel",
        "socks5://tunnel:0",
        "socks5://tunnel:99999",
        "socks5://user:pass@tunnel:21001",
        "socks5://tunnel:21001/path",
        "socks5://tunnel:21001?x=1",
        "socks5://:21001",
    ],
)
def test_normalize_proxy_rejects_other_forms(raw: str) -> None:
    with pytest.raises(ValueError, match="socks5"):
        normalize_proxy(raw)


def test_models_validate_proxy() -> None:
    assert DeviceAddress(host="192.168.1.64", proxy="socks5://tunnel:21001").proxy == "socks5://tunnel:21001"
    assert DeviceAddress(host="192.168.1.64").proxy is None
    assert MonitorTarget(camera_id="c1", host="h", proxy="socks5h://tunnel:21001").proxy == "socks5h://tunnel:21001"
    with pytest.raises(ValueError):
        DeviceAddress(host="h", proxy="http://tunnel:3128")


def test_api_rejects_invalid_proxy_without_echoing_password(gateway: Gateway) -> None:
    res = gateway.post(
        "/v1/devices/info", {"host": "h", "username": "u", "password": "bi-mat-9", "proxy": "http://x:1"}
    )
    assert res.status_code == 422
    assert res.json()["code"] == "VALIDATION_ERROR"
    assert "bi-mat-9" not in res.text


# ---- cache client theo proxy ----------------------------------------------------------------------------


@pytest.mark.anyio
async def test_router_caches_one_client_per_proxy() -> None:
    async with httpx.AsyncClient() as direct:
        router = TunnelRouter(direct)
        assert router.client(None) is direct
        assert router.client("") is direct
        a = router.client("socks5://tunnel:21001")
        assert a is not direct
        assert router.client("SOCKS5://TUNNEL:21001/") is a
        b = router.client("socks5://tunnel:21002")
        assert b is not a
        assert router.proxies == ["socks5://tunnel:21001", "socks5://tunnel:21002"]
        await router.aclose()
        assert router.proxies == []
        assert a.is_closed and b.is_closed and not direct.is_closed


def test_session_cache_separates_sites_with_same_lan_address() -> None:
    cache = SessionCache(60)
    http = httpx.AsyncClient()
    site_a = OnvifClient(DeviceTarget("192.168.1.64", 80, "u", "p", "socks5://tunnel:21001"), http, cache=cache)
    site_b = OnvifClient(DeviceTarget("192.168.1.64", 80, "u", "p", "socks5://tunnel:21002"), http, cache=cache)
    direct = OnvifClient(DeviceTarget("192.168.1.64", 80, "u", "p"), http, cache=cache)
    assert len({site_a._key, site_b._key, direct._key}) == 3


# ---- đi trọn đường qua proxy ---------------------------------------------------------------------------


def _site_device(sim: RunningSim, proxy: str | None) -> dict[str, Any]:
    return {**sim.device(), "host": SITE_HOST, "proxy": proxy}


def test_device_info_through_socks_proxy(gateway: Gateway, sim_fixed: RunningSim, socks: Socks5Server) -> None:
    # Không có proxy: Gateway không phân giải được địa chỉ LAN của site
    direct = gateway.post("/v1/devices/info", _site_device(sim_fixed, None))
    assert direct.status_code == 424
    assert direct.json()["code"] == "UNREACHABLE"

    res = gateway.post("/v1/devices/info", _site_device(sim_fixed, socks.url))
    assert res.status_code == 200, res.text
    assert res.json()["model"] == "SIM-FIX"
    # Mọi lời gọi (device_service và XAddr media/events…) đều CONNECT tới địa chỉ site qua proxy
    assert socks.requests
    assert {host for host, _ in socks.requests} == {SITE_HOST}


def test_snapshot_and_commands_through_socks_proxy(
    gateway: Gateway, sim_fixed: RunningSim, socks: Socks5Server
) -> None:
    body = {"device": _site_device(sim_fixed, socks.url)}
    res = gateway.post("/v1/devices/snapshot", body)
    assert res.status_code == 200, res.text
    assert res.content.startswith(b"\xff\xd8")
    assert gateway.post("/v1/devices/network/get", body).status_code == 200
    assert gateway.post("/v1/devices/encoder/get", body).status_code == 200
    assert {host for host, _ in socks.requests} == {SITE_HOST}


def test_proxy_refusing_connection_is_unreachable(gateway: Gateway, socks: Socks5Server) -> None:
    device = {"host": "127.0.0.1", "port": closed_port(), "username": "a", "password": "b", "proxy": socks.url}
    res = gateway.post("/v1/devices/info", device)
    assert res.status_code == 424
    assert res.json()["code"] == "UNREACHABLE"
    assert socks.url in res.json()["detail"]


def test_dead_proxy_is_unreachable(gateway: Gateway, sim_fixed: RunningSim) -> None:
    res = gateway.post("/v1/devices/info", _site_device(sim_fixed, f"socks5://127.0.0.1:{closed_port()}"))
    assert res.status_code == 424
    assert res.json()["code"] == "UNREACHABLE"


def test_monitor_target_checks_through_proxy(gateway: Gateway, sim_fixed: RunningSim, socks: Socks5Server) -> None:
    targets = [
        {"cameraId": "tun-ok", "host": SITE_HOST, "port": sim_fixed.port, "proxy": socks.url},
        {"cameraId": "tun-direct", "host": SITE_HOST, "port": sim_fixed.port},
    ]
    res = gateway.client.put(
        "/v1/monitor/targets",
        json={"targets": targets, "intervalSeconds": 1},
        headers={"X-Internal-Token": TOKEN},
    )
    assert res.status_code == 200, res.text
    assert res.json()[0]["proxy"] == socks.url
    deadline = time.monotonic() + 8
    statuses: dict[str, str] = {}
    while time.monotonic() < deadline:
        items = gateway.client.get("/v1/monitor/targets", headers={"X-Internal-Token": TOKEN}).json()
        statuses = {t["cameraId"]: t["status"] for t in items}
        if statuses == {"tun-ok": "ONLINE", "tun-direct": "OFFLINE"}:
            break
        time.sleep(0.1)
    assert statuses == {"tun-ok": "ONLINE", "tun-direct": "OFFLINE"}
    gateway.client.put(
        "/v1/monitor/targets", json={"targets": [], "intervalSeconds": 30}, headers={"X-Internal-Token": TOKEN}
    )


@pytest.mark.anyio
async def test_rtsp_probe_connects_through_proxy(socks: Socks5Server) -> None:
    async with httpx.AsyncClient() as direct:
        router = TunnelRouter(direct)
        reader, writer = await router.open_connection(SITE_HOST, socks.port, socks.url)
        writer.close()
        assert (SITE_HOST, socks.port) in socks.requests
        with pytest.raises(SocksError):
            await router.open_connection("127.0.0.1", closed_port(), socks.url)
