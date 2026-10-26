"""Liệt kê kênh đầu ghi NVR/DVR (US-03.1) qua ``POST /v1/devices/recorder/channels``."""

from __future__ import annotations

from collections.abc import Iterator

import httpx
import pytest

from app.discovery.recorder_channels import _Group, _pick_names, _source_reports_no_signal
from app.onvif.client import EncoderData, ProfileData, VideoSourceData
from tests.conftest import Gateway, RunningSim, start_sim
from tests.socks5_server import Socks5Server

CHANNEL_KEYS = {
    "channel",
    "name",
    "videoSourceToken",
    "profileToken",
    "subProfileToken",
    "hasSignal",
    "resolution",
    "rtspUrl",
    "subRtspUrl",
}
SIGNAL_URLS = [
    "rtsp://camera-sim:8554/sim-23",
    "rtsp://camera-sim:8554/sim-24",
    "rtsp://camera-sim:8554/sim-20",
]


@pytest.fixture(scope="module")
def nvr() -> Iterator[RunningSim]:
    running = start_sim(
        name="CamHub NVR Sim",
        manufacturer="Hikvision",
        model="DS-7616NI-K2 (SIM)",
        mac="02:00:00:00:00:31",
        ptz=False,
        zones=False,
        channels=16,
        signal_channels=[1, 2, 3],
        channel_rtsp_main=SIGNAL_URLS,
    )
    yield running
    running.stop()


def test_nvr_lists_sixteen_channels_three_with_signal(gateway: Gateway, nvr: RunningSim) -> None:
    res = gateway.post("/v1/devices/recorder/channels", {"device": nvr.device()})
    assert res.status_code == 200, res.text
    body = res.json()
    assert set(body) == {"manufacturer", "model", "channelCount", "channels"}
    assert (body["manufacturer"], body["model"], body["channelCount"]) == ("Hikvision", "DS-7616NI-K2 (SIM)", 16)
    channels = body["channels"]
    assert [c["channel"] for c in channels] == list(range(1, 17))
    assert all(set(c) == CHANNEL_KEYS for c in channels)
    assert channels[0]["name"] == "Kênh 01 – Cổng"
    assert channels[15]["name"] == "Kênh 16 – Cổng phụ"

    live = [c for c in channels if c["hasSignal"]]
    assert [c["channel"] for c in live] == [1, 2, 3]
    assert [c["rtspUrl"] for c in live] == SIGNAL_URLS
    assert [c["subRtspUrl"] for c in live] == SIGNAL_URLS  # luồng phụ mặc định trùng luồng chính
    first = channels[0]
    assert (first["videoSourceToken"], first["profileToken"], first["subProfileToken"]) == (
        "VideoSource_1",
        "Profile_101",
        "Profile_102",
    )
    assert first["resolution"] == {"width": 1920, "height": 1080}

    dark = channels[4]
    assert dark["hasSignal"] is False
    assert dark["profileToken"] == "Profile_501"
    # Kênh trống vẫn có URI do thiết bị trả, nhưng không phát được
    assert dark["rtspUrl"] == "rtsp://127.0.0.1:554/Streaming/Channels/501"


def test_nvr_signal_change_is_reflected(gateway: Gateway, nvr: RunningSim) -> None:
    httpx.post(f"{nvr.base_url}/sim/channels/7/signal", json={"signal": True}).raise_for_status()
    try:
        channels = gateway.post("/v1/devices/recorder/channels", {"device": nvr.device()}).json()["channels"]
        assert [c["channel"] for c in channels if c["hasSignal"]] == [1, 2, 3, 7]
    finally:
        httpx.post(f"{nvr.base_url}/sim/channels/7/signal", json={"signal": False}).raise_for_status()


def test_nvr_info_reports_recorder_capabilities(gateway: Gateway, nvr: RunningSim) -> None:
    info = gateway.post("/v1/devices/info", nvr.device()).json()
    assert info["model"] == "DS-7616NI-K2 (SIM)"
    assert info["capabilities"]["edgeRecording"] is True
    assert info["capabilities"]["ptz"] is False
    assert len(info["profiles"]) == 32


def test_nvr_channels_through_proxy(gateway: Gateway, nvr: RunningSim) -> None:
    socks = Socks5Server({"nvr.site-lan": "127.0.0.1"}).start()
    try:
        device = {**nvr.device(), "host": "nvr.site-lan", "proxy": socks.url}
        res = gateway.post("/v1/devices/recorder/channels", {"device": device})
        assert res.status_code == 200, res.text
        assert res.json()["channelCount"] == 16
        assert {h for h, _ in socks.requests} == {"nvr.site-lan"}
    finally:
        socks.stop()


def test_single_camera_is_one_channel(gateway: Gateway, sim_fixed: RunningSim) -> None:
    body = gateway.post("/v1/devices/recorder/channels", {"device": sim_fixed.device()}).json()
    assert body["channelCount"] == 1
    (channel,) = body["channels"]
    assert channel["hasSignal"] is True
    assert (channel["profileToken"], channel["subProfileToken"]) == ("profile_main", "profile_sub")
    assert channel["rtspUrl"] == "rtsp://127.0.0.1:8554/sim-main"
    assert channel["subRtspUrl"] == "rtsp://127.0.0.1:8554/sim-sub"


def test_wrong_password_is_auth_failed(gateway: Gateway, nvr: RunningSim) -> None:
    res = gateway.post("/v1/devices/recorder/channels", {"device": nvr.device(password="sai")})
    assert res.status_code == 424
    assert res.json()["code"] == "AUTH_FAILED"


# ---- quy tắc suy đoán ----------------------------------------------------------------------------------


def _profile(token: str, source: str, width: int, name: str = "Profile", config_name: str | None = None) -> ProfileData:
    enc = EncoderData(token, "", "H264", width, width * 9 // 16, 25, 1024, 50, None, None, False)
    return ProfileData(token, name, source, enc, False, False, False, video_source_config_name=config_name)


def test_names_fall_back_when_duplicated() -> None:
    groups = [
        _Group(VideoSourceData("a", 25, (1920, 1080)), [_profile("a1", "a", 1920, "Cổng", "VideoSourceConfig")]),
        _Group(VideoSourceData("b", 25, (1920, 1080)), [_profile("b1", "b", 1920, "Sảnh", "VideoSourceConfig")]),
        _Group(VideoSourceData("c", 25, (1920, 1080)), [_profile("c1", "c", 1920, "main", "VideoSourceConfig")]),
        _Group(VideoSourceData("d", 25, (1920, 1080)), [_profile("d1", "d", 1920, "main", "VideoSourceConfig")]),
    ]
    assert _pick_names(groups) == ["Cổng", "Sảnh", "Kênh 03", "Kênh 04"]


def test_main_is_highest_and_sub_is_lowest_resolution() -> None:
    group = _Group(
        VideoSourceData("a", None, None),
        [_profile("third", "a", 704), _profile("main", "a", 1920), _profile("sub", "a", 640)],
    )
    assert (group.main and group.main.token, group.sub and group.sub.token) == ("main", "sub")
    single = _Group(VideoSourceData("b", None, None), [_profile("only", "b", 1280)])
    assert single.sub is None


@pytest.mark.parametrize(
    ("source", "no_signal"),
    [
        (VideoSourceData("a", 25, (1920, 1080)), False),
        (VideoSourceData("a", None, None), False),
        (VideoSourceData("a", 0, (1920, 1080)), True),
        (VideoSourceData("a", 25, (0, 0)), True),
    ],
)
def test_source_signal_heuristic(source: VideoSourceData, no_signal: bool) -> None:
    assert _source_reports_no_signal(source) is no_signal
