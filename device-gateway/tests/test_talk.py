"""Đàm thoại (US-15): G.711, đọc SDP backchannel và luồng đầy đủ Gateway → RTSP backchannel của bộ giả lập."""

from __future__ import annotations

import math
import socket
import struct
import time
from collections.abc import Iterator
from typing import Any

import httpx
import pytest
from starlette.websockets import WebSocketDisconnect

from app.talk import g711
from app.talk.rtsp import parse_backchannel, strip_credentials
from tests.conftest import TOKEN, Gateway, RunningSim, start_sim


def free_tcp_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return int(s.getsockname()[1])


@pytest.fixture(scope="module")
def sim_talk() -> Iterator[RunningSim]:
    running = start_sim(name="CamHub Sim Speaker", mac="02:00:00:00:00:15", talk_port=free_tcp_port())
    yield running
    running.stop()


def tone(seconds: float, freq: float = 440.0) -> bytes:
    n = int(8000 * seconds)
    return b"".join(struct.pack("<h", int(8000 * math.sin(2 * math.pi * freq * i / 8000))) for i in range(n))


# ---- Đơn vị -------------------------------------------------------------------------------------------


def test_g711_reference_values() -> None:
    # Im lặng: μ-law 0xFF, A-law 0xD5; biên độ cực đại theo bảng ITU-T
    assert g711.ulaw(0) == 0xFF
    assert g711.ulaw(32767) == 0x80
    assert g711.ulaw(-32768) == 0x00
    assert g711.alaw(0) == 0xD5
    assert g711.alaw(32767) == 0xAA
    assert g711.alaw(-32768) == 0x2A
    assert g711.encode(struct.pack("<3h", 0, 0, 0), "PCMU") == b"\xff\xff\xff"
    assert g711.encode(struct.pack("<2h", 0, 0) + b"\x01", "PCMA") == b"\xd5\xd5"


def test_parse_backchannel_picks_sendonly_g711_track() -> None:
    sdp = (
        "v=0\r\nm=video 0 RTP/AVP 96\r\na=control:trackID=1\r\na=recvonly\r\n"
        "m=audio 0 RTP/AVP 97\r\na=rtpmap:97 MPEG4-GENERIC/16000\r\na=control:trackID=2\r\na=recvonly\r\n"
        "m=audio 0 RTP/AVP 8 0\r\na=control:trackID=4\r\na=rtpmap:8 PCMA/8000\r\na=sendonly\r\n"
    )
    track = parse_backchannel(sdp, "rtsp://10.0.0.5:554/Streaming/Channels/101/")
    assert track is not None
    assert (track.control, track.payload_type, track.codec) == (
        "rtsp://10.0.0.5:554/Streaming/Channels/101/trackID=4",
        8,
        "PCMA",
    )
    # Chỉ có âm thanh nhận (recvonly) hoặc codec khác G.711 → không có backchannel
    assert parse_backchannel(sdp.split("m=audio 0 RTP/AVP 8")[0], "rtsp://x/") is None


def test_strip_credentials_from_stream_uri() -> None:
    assert strip_credentials("rtsp://admin:p%40ss@10.0.0.5:554/live") == ("rtsp://10.0.0.5:554/live", "admin", "p%40ss")
    assert strip_credentials("rtsp://10.0.0.5/live") == ("rtsp://10.0.0.5/live", None, None)


# ---- Qua Gateway tới bộ giả lập ----------------------------------------------------------------------


def test_push_to_talk_sends_g711_rtp_to_camera_speaker(gateway: Gateway, sim_talk: RunningSim) -> None:
    info = gateway.post("/v1/devices/info", sim_talk.device())
    assert info.json()["capabilities"]["audioOut"] is True
    # Profile chỉ có loa không xuất hiện thành luồng video
    assert {p["type"] for p in info.json()["profiles"]} == {"MAIN", "SUB"}

    res = gateway.post("/v1/talk/sessions", {"device": sim_talk.device()})
    assert res.status_code == 201, res.text
    created = res.json()
    assert created["codec"] == "PCMU"
    assert created["sampleRate"] == 8000
    session_id = created["sessionId"]

    # Camera chỉ nhận một người nói
    busy = gateway.post("/v1/talk/sessions", {"device": sim_talk.device()})
    assert busy.status_code == 424
    assert busy.json()["code"] == "BUSY"

    with gateway.client.websocket_connect(f"/talk/ws/{session_id}") as ws:
        audio = tone(1.0)
        for i in range(0, len(audio), 640):  # khung 40 ms như trình duyệt gửi
            ws.send_bytes(audio[i : i + 640])
        time.sleep(0.3)
        ws.send_text("end")

    status = poll_status(gateway, session_id, "ENDED")
    assert status["audioSeconds"] == pytest.approx(1.0, abs=0.05)
    assert status["durationSeconds"] > 0
    stats = httpx.get(f"{sim_talk.base_url}/sim/talk").json()
    assert stats["packets"] == 50
    assert stats["bytes"] == 8000
    assert stats["payloadType"] == 0

    # Mã phiên chỉ dùng một lần
    with pytest.raises(WebSocketDisconnect) as closed, gateway.client.websocket_connect(f"/talk/ws/{session_id}") as ws:
        ws.receive_bytes()
    assert closed.value.code == 4404

    # Phiên kết thúc thì camera nhận người nói mới
    again = gateway.post("/v1/talk/sessions", {"device": sim_talk.device()})
    assert again.status_code == 201
    closed_now = gateway.client.delete(
        f"/v1/talk/sessions/{again.json()['sessionId']}", headers={"X-Internal-Token": TOKEN}
    )
    assert closed_now.json()["state"] == "ENDED"
    assert closed_now.json()["durationSeconds"] == 0


def test_camera_without_speaker_is_not_supported(gateway: Gateway, sim: RunningSim) -> None:
    res = gateway.post("/v1/talk/sessions", {"device": sim.device()})
    assert res.status_code == 424
    assert res.json()["code"] == "NOT_SUPPORTED"


def test_unknown_session_websocket_is_rejected(gateway: Gateway) -> None:
    with pytest.raises(WebSocketDisconnect) as closed, gateway.client.websocket_connect("/talk/ws/khong-co") as ws:
        ws.receive_bytes()
    assert closed.value.code == 4404


def test_talk_rest_requires_internal_token(gateway: Gateway) -> None:
    assert gateway.client.get("/v1/talk/sessions/x").status_code == 401


def poll_status(gateway: Gateway, session_id: str, state: str) -> dict[str, Any]:
    deadline = time.monotonic() + 5
    while True:
        status = gateway.client.get(f"/v1/talk/sessions/{session_id}", headers={"X-Internal-Token": TOKEN}).json()
        if status["state"] == state or time.monotonic() > deadline:
            assert status["state"] == state, status
            return status
        time.sleep(0.05)
