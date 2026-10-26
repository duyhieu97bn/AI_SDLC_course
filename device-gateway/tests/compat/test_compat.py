"""Bộ kiểm thử tương thích thiết bị (TS-05.4): mỗi phép thử chạy cho từng thiết bị qua REST của Gateway.

``pytest -m compat`` chỉ chạy bộ này. Phép thử bỏ qua vì thiết bị không khai báo năng lực có lý do bắt đầu
bằng "KHÔNG HỖ TRỢ" để báo cáo phân biệt với "BỎ QUA".
"""

from __future__ import annotations

import time
from typing import Any

import httpx
import pytest

from tests.compat.conftest import ROWS, DeviceCase
from tests.compat.report import UNSUPPORTED
from tests.conftest import TOKEN, Gateway

pytestmark = pytest.mark.compat


def _ok(res: httpx.Response, status: int = 200) -> Any:
    assert res.status_code == status, f"HTTP {res.status_code}: {res.text[:300]}"
    return res.json() if res.content and res.headers.get("content-type", "").startswith("application/json") else None


def info_of(gateway: Gateway, case: DeviceCase) -> dict[str, Any]:
    if "info" not in case.cache:
        case.cache["info"] = _ok(gateway.post("/v1/devices/info", case.device()))
    info: dict[str, Any] = case.cache["info"]
    return info


def require(gateway: Gateway, case: DeviceCase, capability: str) -> None:
    if not info_of(gateway, case)["capabilities"][capability]:
        pytest.skip(f"{UNSUPPORTED}: thiết bị không khai báo {capability}")


def main_profile(gateway: Gateway, case: DeviceCase) -> dict[str, Any]:
    profiles = info_of(gateway, case)["profiles"]
    return next((p for p in profiles if p["type"] == "MAIN"), profiles[0])


def test_info(gateway: Gateway, device_case: DeviceCase) -> None:
    info = info_of(gateway, device_case)
    assert info["manufacturer"] and info["model"]
    row = ROWS[device_case.id]
    row.model, row.firmware = info["model"] or "", info["firmware"] or ""


def test_stream_profiles(gateway: Gateway, device_case: DeviceCase) -> None:
    profiles = info_of(gateway, device_case)["profiles"]
    assert profiles, "Không có profile video"
    assert [p["type"] for p in profiles].count("MAIN") == 1
    main = main_profile(gateway, device_case)
    assert main["width"] > 0 and main["height"] > 0 and main["codec"]
    assert str(main["rtspUrl"]).lower().startswith("rtsp://")


def test_snapshot(gateway: Gateway, device_case: DeviceCase) -> None:
    res = gateway.post("/v1/devices/snapshot", {"device": device_case.device()})
    if res.status_code == 424 and res.json()["code"] == "NOT_SUPPORTED":
        pytest.skip(f"{UNSUPPORTED}: {res.json()['detail']}")
    assert res.status_code == 200, res.text[:300]
    assert res.headers["content-type"].startswith("image/") and len(res.content) > 100


def test_ptz(gateway: Gateway, device_case: DeviceCase) -> None:
    require(gateway, device_case, "ptz")
    token = main_profile(gateway, device_case)["token"]
    move = {"device": device_case.device(), "profileToken": token, "pan": 0.2, "tilt": 0.0, "zoom": 0.0}
    _ok(gateway.post("/v1/devices/ptz/move", {**move, "timeoutMs": 1000}), 204)
    if device_case.sim:
        assert device_case.sim.simulator.state.ptz_velocity == (0.2, 0.0, 0.0)
    time.sleep(0.3)
    _ok(gateway.post("/v1/devices/ptz/stop", {"device": device_case.device(), "profileToken": token}), 204)
    if device_case.sim:
        assert device_case.sim.simulator.state.ptz_velocity == (0.0, 0.0, 0.0)


def test_presets(gateway: Gateway, device_case: DeviceCase) -> None:
    require(gateway, device_case, "ptz")
    base = {"device": device_case.device(), "profileToken": main_profile(gateway, device_case)["token"]}
    presets = _ok(gateway.post("/v1/devices/ptz/presets", base))
    assert isinstance(presets, list)
    if not device_case.allow_write:
        return
    token = _ok(gateway.post("/v1/devices/ptz/presets/set", {**base, "name": "camhub-compat"}))["token"]
    try:
        names = {p["token"]: p["name"] for p in _ok(gateway.post("/v1/devices/ptz/presets", base))}
        assert names.get(token) == "camhub-compat"
        _ok(gateway.post("/v1/devices/ptz/presets/goto", {**base, "presetToken": token}), 204)
    finally:
        _ok(gateway.post("/v1/devices/ptz/presets/remove", {**base, "presetToken": token}), 204)
    tokens = {p["token"] for p in _ok(gateway.post("/v1/devices/ptz/presets", base))}
    assert token not in tokens


def test_imaging(gateway: Gateway, device_case: DeviceCase) -> None:
    require(gateway, device_case, "imaging")
    device = device_case.device()
    current = _ok(gateway.post("/v1/devices/imaging/get", {"device": device}))
    assert current["videoSourceToken"]
    if not device_case.allow_write or current["brightness"] is None:
        return
    original = {k: current[k] for k in ("brightness", "contrast", "irMode", "wdr") if current[k] is not None}
    changed = dict(original)
    if device_case.is_sim:
        changed.update(brightness=63.0, irMode="NIGHT", wdr=True)
    _ok(gateway.post("/v1/devices/imaging/set", {"device": device, "settings": changed}), 204)
    try:
        after = _ok(gateway.post("/v1/devices/imaging/get", {"device": device}))
        assert after["brightness"] == pytest.approx(changed["brightness"], abs=1)
        if device_case.is_sim:
            assert (after["irMode"], after["wdr"]) == ("NIGHT", True)
            assert device_case.sim is not None and device_case.sim.simulator.state.imaging.ir_cut == "OFF"
    finally:
        _ok(gateway.post("/v1/devices/imaging/set", {"device": device, "settings": original}), 204)


def test_encoder(gateway: Gateway, device_case: DeviceCase) -> None:
    configs = _ok(gateway.post("/v1/devices/encoder/get", {"device": device_case.device()}))
    assert configs, "Không có cấu hình bộ mã hóa"
    assert all(c["token"] and c["width"] > 0 for c in configs)
    assert any(c["options"] and c["options"]["codecs"] for c in configs)


def test_network(gateway: Gateway, device_case: DeviceCase) -> None:
    net = _ok(gateway.post("/v1/devices/network/get", {"device": device_case.device()}))
    assert net["deviceTime"] and net["macAddress"]
    assert isinstance(net["ntpServers"], list)


def test_events(gateway: Gateway, device_case: DeviceCase) -> None:
    require(gateway, device_case, "events")
    camera_id = device_case.camera_id
    headers = {"X-Internal-Token": TOKEN}
    _ok(gateway.post("/v1/events/subscriptions", {"cameraId": camera_id, "device": device_case.device()}), 202)
    try:
        deadline = time.monotonic() + 10
        status: dict[str, Any] = {}
        while time.monotonic() < deadline:
            status = gateway.client.get(f"/v1/events/subscriptions/{camera_id}", headers=headers).json()
            if status["status"] in ("ACTIVE", "UNSUPPORTED"):
                break
            time.sleep(0.05)
        assert status["status"] == "ACTIVE", status
        if device_case.sim is None:
            return  # camera thật: chỉ kiểm tra đăng ký PullPoint hoạt động
        httpx.post(f"{device_case.sim.base_url}/sim/motion", json={"state": True})
        deadline = time.monotonic() + 8
        mine: list[dict[str, Any]] = []
        while time.monotonic() < deadline and not mine:
            mine = [p for k, p in gateway.sink.messages if k == "motion.detected" and p["cameraId"] == camera_id]
            time.sleep(0.05)
        assert mine and mine[0]["state"] is True, "Không nhận được sự kiện chuyển động"
        httpx.post(f"{device_case.sim.base_url}/sim/motion", json={"state": False})
    finally:
        gateway.client.delete(f"/v1/events/subscriptions/{camera_id}", headers=headers)
