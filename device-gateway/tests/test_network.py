"""Mạng và thời gian của thiết bị (US-25.1): đọc độ lệch đồng hồ, đổi IP/DNS/gateway, đổi nguồn giờ."""

from __future__ import annotations

from collections.abc import Iterator
from datetime import UTC, datetime, timedelta
from typing import Any

import httpx
import pytest

from app.models import NetworkSetRequest, TimeSetRequest
from tests.conftest import Gateway, RunningSim, start_sim

NETWORK_KEYS = {
    "ipAddress",
    "prefixLength",
    "gateway",
    "dns",
    "macAddress",
    "hostname",
    "ntpServers",
    "ntpFromDhcp",
    "fromDhcp",
    "timeMode",
    "timezone",
    "deviceTime",
    "clockDriftSeconds",
}


@pytest.fixture
def cam() -> Iterator[RunningSim]:
    """Camera riêng cho mỗi test vì test đổi mạng/giờ của thiết bị."""
    running = start_sim(name="CamHub Sim Net", mac="02:00:00:00:00:25", clock_offset_seconds=7, ntp_sync=True)
    yield running
    running.stop()


def _network(gateway: Gateway, sim: RunningSim) -> Any:
    res = gateway.post("/v1/devices/network/get", {"device": sim.device()})
    assert res.status_code == 200, res.text
    return res.json()


def test_network_get_reports_signed_drift(gateway: Gateway, cam: RunningSim) -> None:
    net = _network(gateway, cam)
    assert set(net) == NETWORK_KEYS
    assert 6.0 <= net["clockDriftSeconds"] <= 8.0
    assert (net["ipAddress"], net["prefixLength"], net["fromDhcp"]) == ("127.0.0.1", 24, False)
    assert (net["timeMode"], net["timezone"]) == ("MANUAL", "ICT-7")
    # Thiết bị chạy chậm thì độ lệch âm
    httpx.post(f"{cam.base_url}/sim/clock", json={"offsetSeconds": -30}).raise_for_status()
    assert -31.0 <= _network(gateway, cam)["clockDriftSeconds"] <= -29.0


def test_network_set_static(gateway: Gateway, cam: RunningSim) -> None:
    body = {
        "device": cam.device(),
        "dhcp": False,
        "address": "192.168.10.25",
        "prefixLength": 24,
        "gateway": "192.168.10.1",
        "dns": ["8.8.8.8", "1.1.1.1"],
    }
    res = gateway.post("/v1/devices/network/set", body)
    assert res.status_code == 200, res.text
    assert res.json() == {"rebootNeeded": False, "newHost": "192.168.10.25"}
    net = _network(gateway, cam)
    assert (net["ipAddress"], net["prefixLength"], net["gateway"]) == ("192.168.10.25", 24, "192.168.10.1")
    assert net["dns"] == ["8.8.8.8", "1.1.1.1"]
    # Cùng địa chỉ thì không có host mới; chuyển sang DHCP thì thiết bị báo cần khởi động lại
    assert gateway.post("/v1/devices/network/set", body).json()["newHost"] is None
    res = gateway.post("/v1/devices/network/set", {"device": cam.device(), "dhcp": True})
    assert res.json() == {"rebootNeeded": True, "newHost": None}
    net = _network(gateway, cam)
    assert net["fromDhcp"] is True and net["ipAddress"] == "127.0.0.1"


@pytest.mark.parametrize(
    "patch",
    [
        {"dhcp": False},
        {"dhcp": False, "address": "192.168.1.10"},
        {"dhcp": False, "address": "192.168.1.300", "prefixLength": 24},
        {"dhcp": False, "address": "192.168.1.0", "prefixLength": 24},
        {"dhcp": False, "address": "127.0.0.5", "prefixLength": 8},
        {"dhcp": False, "address": "192.168.1.10", "prefixLength": 33},
        {"dhcp": False, "address": "192.168.1.10", "prefixLength": 24, "gateway": "10.0.0.1"},
        {"dhcp": False, "address": "192.168.1.10", "prefixLength": 24, "dns": ["dns.google"]},
        {"dhcp": False, "address": "192.168.1.10", "prefixLength": 24, "dns": []},
        {"dhcp": True, "address": "192.168.1.10"},
        {"dhcp": True, "gateway": "192.168.1.1"},
    ],
)
def test_network_set_validation(gateway: Gateway, cam: RunningSim, patch: dict[str, Any]) -> None:
    res = gateway.post("/v1/devices/network/set", {"device": cam.device(), **patch})
    assert res.status_code == 422, res.text
    assert res.json()["code"] == "VALIDATION_ERROR"


def test_time_set_manual_then_ntp(gateway: Gateway, cam: RunningSim) -> None:
    target = datetime.now(UTC) + timedelta(minutes=10)
    res = gateway.post(
        "/v1/devices/time/set",
        {"device": cam.device(), "mode": "MANUAL", "utc": target.isoformat(), "timezone": "<+07>-7"},
    )
    assert res.status_code == 204, res.text
    net = _network(gateway, cam)
    # Giờ đặt và giờ đọc ONVIF đều chỉ đến giây: sai số tới ±1 giây
    assert 598.5 <= net["clockDriftSeconds"] <= 601
    assert (net["timeMode"], net["timezone"]) == ("MANUAL", "<+07>-7")
    # Đồng hồ đổi 10 phút nhưng Gateway vẫn xác thực được (đã bỏ độ lệch nhớ trong phiên)
    res = gateway.post(
        "/v1/devices/time/set",
        {"device": cam.device(), "mode": "NTP", "ntpServers": ["10.0.0.1", "time.camhub.vn"], "timezone": "ICT-7"},
    )
    assert res.status_code == 204, res.text
    net = _network(gateway, cam)
    assert net["timeMode"] == "NTP" and net["ntpServers"] == ["10.0.0.1", "time.camhub.vn"]
    # Bộ giả lập bật ntp_sync: chuyển sang NTP thì đồng hồ hết lệch
    assert abs(net["clockDriftSeconds"]) <= 1.0
    assert cam.simulator.state.timezone == "ICT-7"


@pytest.mark.parametrize(
    "patch",
    [
        {"mode": "MANUAL"},
        {"mode": "MANUAL", "utc": "2026-10-25T10:00:00Z", "ntpServers": ["pool.ntp.org"]},
        {"mode": "MANUAL", "utc": "1990-01-01T00:00:00Z"},
        {"mode": "NTP", "utc": "2026-10-25T10:00:00Z"},
        {"mode": "NTP", "ntpServers": []},
        {"mode": "NTP", "ntpServers": ["bad host!"]},
        {"mode": "NTP", "timezone": "Asia/Ho_Chi_Minh"},
        {"mode": "NTP", "timezone": "ICT-7</tt:TZ>"},
        {"mode": "AUTO"},
    ],
)
def test_time_set_validation(gateway: Gateway, cam: RunningSim, patch: dict[str, Any]) -> None:
    res = gateway.post("/v1/devices/time/set", {"device": cam.device(), **patch})
    assert res.status_code == 422, res.text


@pytest.mark.parametrize("tz", ["ICT-7", "<+07>-7", "UTC0", "CET-1CEST,M3.5.0,M10.5.0/3", "CST6CDT,M3.2.0,M11.1.0"])
def test_posix_timezones_accepted(tz: str) -> None:
    device = {"host": "10.0.0.1"}
    assert TimeSetRequest.model_validate({"device": device, "mode": "NTP", "timezone": tz}).timezone == tz


def test_point_to_point_prefix_allows_any_address() -> None:
    body = {"device": {"host": "10.0.0.1"}, "dhcp": False, "address": "10.0.0.0", "prefixLength": 31}
    assert NetworkSetRequest.model_validate(body).address == "10.0.0.0"


def test_device_errors_are_424(gateway: Gateway, sim_fixed: RunningSim) -> None:
    device = sim_fixed.device(password="sai-mat-khau")
    res = gateway.post("/v1/devices/time/set", {"device": device, "mode": "NTP"})
    assert res.status_code == 424 and res.json()["code"] == "AUTH_FAILED"
    res = gateway.post("/v1/devices/network/set", {"device": device, "dhcp": True})
    assert res.status_code == 424 and res.json()["code"] == "AUTH_FAILED"
