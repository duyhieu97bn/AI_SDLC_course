"""REST /v1 chạy qua Gateway thật tới bộ giả lập ONVIF trên localhost."""

from __future__ import annotations

import socket
import time

import httpx

from tests.conftest import TOKEN, Gateway, RunningSim

INFO_KEYS = {"manufacturer", "model", "firmware", "serialNumber", "macAddress", "capabilities", "profiles"}
CAP_KEYS = {"ptz", "audioIn", "audioOut", "imaging", "events", "edgeRecording", "motionDetection", "privacyMask"}
PROFILE_KEYS = {"token", "name", "type", "codec", "width", "height", "fps", "bitrateKbps", "rtspUrl", "bFrames"}
DISCOVERY_KEYS = {"host", "port", "manufacturer", "model", "macAddress", "hardware", "scopes"}


def closed_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return int(s.getsockname()[1])


def test_info_matches_core_records_exactly(gateway: Gateway, sim: RunningSim) -> None:
    res = gateway.post("/v1/devices/info", sim.device())
    assert res.status_code == 200, res.text
    info = res.json()
    assert set(info) == INFO_KEYS
    assert set(info["capabilities"]) == CAP_KEYS
    assert info["manufacturer"] == "CamHub"
    assert info["model"] == "SIM-PTZ"
    assert info["macAddress"] == "02:00:00:00:00:01"
    caps = info["capabilities"]
    assert caps["ptz"] and caps["imaging"] and caps["events"] and caps["motionDetection"] and caps["audioIn"]
    assert caps["privacyMask"] and not caps["edgeRecording"]
    main, sub = info["profiles"]
    assert set(main) == PROFILE_KEYS
    assert (main["type"], main["width"], main["height"], main["codec"]) == ("MAIN", 1920, 1080, "H264")
    assert (sub["type"], sub["width"], sub["height"]) == ("SUB", 640, 360)
    assert main["rtspUrl"] == "rtsp://127.0.0.1:8554/sim-main"
    assert main["bFrames"] is True and sub["bFrames"] is False
    assert main["fps"] == 25 and main["bitrateKbps"] == 4096


def test_fixed_camera_has_no_ptz_and_no_bframes(gateway: Gateway, sim_fixed: RunningSim) -> None:
    info = gateway.post("/v1/devices/info", sim_fixed.device()).json()
    assert info["capabilities"]["ptz"] is False
    assert info["capabilities"]["privacyMask"] is False
    assert all(p["bFrames"] is False for p in info["profiles"])
    res = gateway.post("/v1/devices/ptz/stop", {"device": sim_fixed.device(), "profileToken": "profile_main"})
    assert res.status_code == 424
    assert res.json()["code"] == "NOT_SUPPORTED"


def test_wrong_password_is_auth_failed(gateway: Gateway, sim: RunningSim) -> None:
    res = gateway.post("/v1/devices/info", sim.device(password="sai-mat-khau"))
    assert res.status_code == 424
    assert res.json()["code"] == "AUTH_FAILED"
    assert "sai-mat-khau" not in res.text


def test_closed_port_is_unreachable(gateway: Gateway) -> None:
    device = {"host": "127.0.0.1", "port": closed_port(), "username": "a", "password": "b"}
    res = gateway.post("/v1/devices/info", device)
    assert res.status_code == 424
    assert res.json()["code"] == "UNREACHABLE"


def test_validation_error_does_not_echo_password(gateway: Gateway) -> None:
    res = gateway.post("/v1/devices/info", {"host": "", "port": 99999, "password": "bi-mat-123"})
    assert res.status_code == 422
    assert "bi-mat-123" not in res.text


def test_discovery_unicast_finds_simulator(gateway: Gateway, sim: RunningSim) -> None:
    res = gateway.post("/v1/discovery", {"targets": ["127.0.0.1/32"], "timeoutSeconds": 1})
    assert res.status_code == 200, res.text
    found = res.json()
    assert len(found) == 1
    device = found[0]
    assert set(device) == DISCOVERY_KEYS
    assert (device["host"], device["port"]) == ("127.0.0.1", sim.port)
    assert device["manufacturer"] == "CamHub"
    assert device["hardware"] == "SIM-PTZ"
    assert device["macAddress"] == "02:00:00:00:00:01"


def test_discovery_rejects_bad_target(gateway: Gateway) -> None:
    res = gateway.post("/v1/discovery", {"targets": ["not-an-ip"], "timeoutSeconds": 1})
    assert res.status_code == 422
    assert res.json()["code"] == "INVALID_TARGET"


def test_snapshot_returns_jpeg(gateway: Gateway, sim: RunningSim) -> None:
    res = gateway.post("/v1/devices/snapshot", {"device": sim.device()})
    assert res.status_code == 200, res.text
    assert res.headers["content-type"] == "image/jpeg"
    assert res.content.startswith(b"\xff\xd8")


def test_encoder_set_roundtrip_and_validation(gateway: Gateway, sim: RunningSim) -> None:
    device = sim.device()
    configs = gateway.post("/v1/devices/encoder/get", {"device": device}).json()
    sub = next(c for c in configs if c["token"] == "vec_sub")
    assert "H265" in sub["options"]["codecs"]
    assert {"width": 704, "height": 576} in sub["options"]["resolutions"]
    res = gateway.post(
        "/v1/devices/encoder/set",
        {
            "device": device,
            "configurationToken": "vec_sub",
            "codec": "H265",
            "width": 704,
            "height": 576,
            "fps": 12,
            "bitrateKbps": 768,
            "govLength": 24,
        },
    )
    assert res.status_code == 204, res.text
    enc = sim.simulator.state.encoder("vec_sub")
    assert enc is not None
    assert (enc.encoding, enc.width, enc.height, enc.fps, enc.bitrate, enc.gov) == ("H265", 704, 576, 12, 768, 24)
    bad = gateway.post(
        "/v1/devices/encoder/set", {"device": device, "configurationToken": "vec_sub", "width": 999, "height": 111}
    )
    assert bad.status_code == 424 and bad.json()["code"] == "NOT_SUPPORTED"
    # Trả về cấu hình ban đầu cho các test khác
    gateway.post(
        "/v1/devices/encoder/set",
        {
            "device": device,
            "configurationToken": "vec_sub",
            "codec": "H264",
            "width": 640,
            "height": 360,
            "fps": 15,
            "bitrateKbps": 512,
            "govLength": 30,
        },
    )


def test_device_fault_reason_is_reported(gateway: Gateway, sim: RunningSim) -> None:
    res = gateway.post("/v1/devices/imaging/set", {"device": sim.device(), "settings": {"brightness": 150}})
    assert res.status_code == 424
    body = res.json()
    assert body["code"] == "DEVICE_ERROR"
    assert "Brightness" in body["detail"]


def test_ntp_set_and_network_get(gateway: Gateway, sim: RunningSim) -> None:
    device = sim.device()
    res = gateway.post("/v1/devices/ntp/set", {"device": device, "servers": ["10.0.0.1", "time.camhub.vn"]})
    assert res.status_code == 204, res.text
    net = gateway.post("/v1/devices/network/get", {"device": device}).json()
    assert net["ntpServers"] == ["10.0.0.1", "time.camhub.vn"]
    assert net["ipAddress"] == "127.0.0.1"
    assert net["macAddress"] == "02:00:00:00:00:01"
    assert net["fromDhcp"] is False
    # Đồng hồ giả lập lệch +1 giờ
    assert 3590 < net["clockDriftSeconds"] < 3610
    assert sim.simulator.state.date_time_type == "NTP"


def test_reboot_and_password_change(gateway: Gateway, sim_fixed: RunningSim) -> None:
    device = sim_fixed.device()
    res = gateway.post("/v1/devices/reboot", {"device": device})
    assert res.status_code == 200 and res.json()["message"]
    assert sim_fixed.simulator.state.reboots == 1
    res = gateway.post("/v1/devices/password", {"device": device, "username": "admin", "newPassword": "Moi@2026"})
    assert res.status_code == 204, res.text
    assert gateway.post("/v1/devices/info", device).json()["code"] == "AUTH_FAILED"
    changed = sim_fixed.device(password="Moi@2026")
    res = gateway.post("/v1/devices/password", {"device": changed, "username": "admin", "newPassword": "Sim@12345"})
    assert res.status_code == 204
    assert gateway.post("/v1/devices/info", device).status_code == 200


def test_motion_events_are_published(gateway: Gateway, sim: RunningSim) -> None:
    res = gateway.post("/v1/events/subscriptions", {"cameraId": "cam-api", "device": sim.device()})
    assert res.status_code == 202, res.text
    _wait_active(gateway, "cam-api")
    httpx.post(f"{sim.base_url}/sim/motion", json={"state": True})
    started = gateway.wait_messages("motion.detected", camera_id="cam-api")
    assert started and started[-1]["state"] is True
    msg = started[-1]
    assert msg["cameraId"] == "cam-api" and msg["source"] == "DEVICE"
    assert msg["topic"] == "tns1:RuleEngine/CellMotionDetector/Motion"
    assert msg["messageId"] and msg["type"] == "MotionDetected" and msg["occurredAt"]
    httpx.post(f"{sim.base_url}/sim/motion", json={"state": False})
    stopped = gateway.wait_messages("motion.detected", count=2, camera_id="cam-api")
    assert stopped[-1]["state"] is False
    assert gateway.client.delete("/v1/events/subscriptions/cam-api", headers=_auth()).status_code == 204
    assert gateway.client.delete("/v1/events/subscriptions/cam-api", headers=_auth()).status_code == 404


def test_unreachable_camera_publishes_offline(gateway: Gateway) -> None:
    device = {"host": "127.0.0.1", "port": closed_port(), "username": "a", "password": "b"}
    gateway.post("/v1/events/subscriptions", {"cameraId": "cam-off", "device": device})
    offline = gateway.wait_messages("device.offline", camera_id="cam-off")
    assert offline and offline[0]["reason"].startswith("UNREACHABLE")
    status = gateway.client.get("/v1/events/subscriptions/cam-off", headers=_auth()).json()
    assert status["status"] == "OFFLINE"
    gateway.client.delete("/v1/events/subscriptions/cam-off", headers=_auth())


def _auth() -> dict[str, str]:
    return {"X-Internal-Token": TOKEN}


def _wait_active(gateway: Gateway, camera_id: str, timeout: float = 8.0) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        status = gateway.client.get(f"/v1/events/subscriptions/{camera_id}", headers=_auth()).json()
        if status.get("status") == "ACTIVE":
            return
        time.sleep(0.05)
    raise AssertionError(f"Đăng ký {camera_id} không chuyển sang ACTIVE")


def test_subscription_is_renewed_and_unsubscribed_on_delete(gateway: Gateway, sim_fixed: RunningSim) -> None:
    state = sim_fixed.simulator.state
    res = gateway.post("/v1/events/subscriptions", {"cameraId": "cam-renew", "device": sim_fixed.device()})
    assert res.status_code == 202
    _wait_active(gateway, "cam-renew")
    (sub_id,) = state.subscriptions
    # TTL trong test là 4 giây: quá thời hạn mà vẫn cùng PullPoint nghĩa là Renew đã chạy
    time.sleep(5)
    assert list(state.subscriptions) == [sub_id]
    status = gateway.client.get("/v1/events/subscriptions/cam-renew", headers=_auth()).json()
    assert status["status"] == "ACTIVE"
    assert gateway.client.delete("/v1/events/subscriptions/cam-renew", headers=_auth()).status_code == 204
    assert state.subscriptions == {}
