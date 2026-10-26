"""Phát lại bản ghi trên thiết bị (US-22.1, ONVIF Profile G): ``/v1/devices/recordings/search`` và ``/replay-uri``."""

from __future__ import annotations

from collections.abc import Iterator
from datetime import UTC, datetime, timedelta
from typing import Any

import pytest

from app.edge_playback.service import UNSUPPORTED_REASON, build_ranges, match_source, with_start_time
from app.onvif.client import TrackStateEvent
from onvif_sim.recordings import data_intervals
from tests.conftest import Gateway, RunningSim, start_sim
from tests.socks5_server import Socks5Server

NVR_URLS = ["rtsp://camera-sim:8554/sim-23", "rtsp://camera-sim:8554/sim-24"]


@pytest.fixture(scope="module")
def edge_cam() -> Iterator[RunningSim]:
    """Camera có thẻ nhớ (Profile G), đồng hồ lệch +1 giờ để kiểm tra Gateway quy đổi giờ thiết bị."""
    running = start_sim(
        name="CamHub Sim Edge",
        model="SIM-EDGE",
        mac="02:00:00:00:00:41",
        ptz=False,
        zones=False,
        edge_recording=True,
        edge_hours=24,
        clock_offset_seconds=3600,
    )
    yield running
    running.stop()


@pytest.fixture(scope="module")
def edge_nvr() -> Iterator[RunningSim]:
    running = start_sim(
        name="CamHub NVR Edge",
        manufacturer="Hikvision",
        model="DS-7604NI (SIM)",
        mac="02:00:00:00:00:42",
        ptz=False,
        zones=False,
        channels=4,
        signal_channels=[1, 2],
        channel_rtsp_main=NVR_URLS,
    )
    yield running
    running.stop()


def _window(hours: float = 24) -> dict[str, str]:
    now = datetime.now(UTC)
    return {"from": (now - timedelta(hours=hours)).isoformat(), "to": now.isoformat()}


def _search(gateway: Gateway, device: dict[str, Any], **extra: Any) -> Any:
    body = {"device": device, **_window(), **extra}
    res = gateway.post("/v1/devices/recordings/search", body)
    assert res.status_code == 200, res.text
    return res.json()


def _instant(raw: str) -> datetime:
    return datetime.fromisoformat(raw)


def _skip_near_gap(clock_offset_hours: int) -> None:
    """Cửa sổ 24 giờ cắt ngang khoảng trống 02:00–02:30 (giờ thiết bị) thì số khoảng khác 2: bỏ qua cho ổn định."""
    device_now = datetime.now(UTC) + timedelta(hours=clock_offset_hours)
    minute_of_day = device_now.hour * 60 + device_now.minute
    if 119 <= minute_of_day <= 152:
        pytest.skip("Đồng hồ thiết bị đang ở quanh khoảng trống giả lập")


def test_single_camera_search_shows_daily_gap(gateway: Gateway, edge_cam: RunningSim) -> None:
    _skip_near_gap(1)
    body = _search(gateway, edge_cam.device(), videoSourceToken=None)
    assert body["supported"] is True and body["reason"] is None
    (rec,) = body["recordings"]
    assert set(rec) == {"recordingToken", "videoSourceToken", "trackToken", "ranges"}
    assert (rec["recordingToken"], rec["videoSourceToken"], rec["trackToken"]) == ("rec_1", "vs0", "VIDEO001")
    ranges = [(_instant(r["start"]), _instant(r["end"])) for r in rec["ranges"]]
    assert len(ranges) == 2
    (_, gap_start), (gap_end, last_end) = ranges
    assert gap_end - gap_start == timedelta(minutes=30)
    # Khoảng trống 02:00 giờ thiết bị (lệch +1 giờ) là 01:00 giờ thật
    assert (gap_start.hour, gap_start.minute, gap_start.second) == (1, 0, 0)
    assert abs((last_end - datetime.now(UTC)).total_seconds()) < 30
    assert rec["ranges"][0]["start"].endswith("Z")


def test_window_without_data_has_no_ranges(gateway: Gateway, edge_cam: RunningSim) -> None:
    now = datetime.now(UTC)
    window = {"from": (now - timedelta(days=5)).isoformat(), "to": (now - timedelta(days=4)).isoformat()}
    res = gateway.post("/v1/devices/recordings/search", {"device": edge_cam.device(), **window})
    assert res.status_code == 200, res.text
    assert [r["ranges"] for r in res.json()["recordings"]] == [[]]


def test_camera_without_profile_g_is_unsupported(gateway: Gateway, sim_fixed: RunningSim) -> None:
    body = _search(gateway, sim_fixed.device())
    assert body == {"supported": False, "reason": UNSUPPORTED_REASON, "recordings": []}
    info = gateway.post("/v1/devices/info", sim_fixed.device()).json()
    assert info["capabilities"]["edgeRecording"] is False


def test_edge_camera_reports_capability(gateway: Gateway, edge_cam: RunningSim) -> None:
    info = gateway.post("/v1/devices/info", edge_cam.device()).json()
    assert info["capabilities"]["edgeRecording"] is True


def test_nvr_filters_by_video_source(gateway: Gateway, edge_nvr: RunningSim) -> None:
    every = _search(gateway, edge_nvr.device())
    assert [(r["recordingToken"], r["videoSourceToken"]) for r in every["recordings"]] == [
        ("rec_ch01", "VideoSource_1"),
        ("rec_ch02", "VideoSource_2"),
    ]
    _skip_near_gap(0)
    one = _search(gateway, edge_nvr.device(), videoSourceToken="VideoSource_2")
    (rec,) = one["recordings"]
    assert rec["recordingToken"] == "rec_ch02"
    assert len(rec["ranges"]) == 2
    assert _search(gateway, edge_nvr.device(), videoSourceToken="VideoSource_4")["recordings"] == []


def test_replay_uri_adds_start_time_in_device_clock(gateway: Gateway, edge_cam: RunningSim) -> None:
    body = {"device": edge_cam.device(), "recordingToken": "rec_1", "start": "2026-10-24T01:15:30Z"}
    res = gateway.post("/v1/devices/recordings/replay-uri", body)
    assert res.status_code == 200, res.text
    assert res.json() == {"uri": "rtsp://127.0.0.1:8554/sim-main?starttime=20261024T021530Z"}


def test_replay_uri_for_nvr_channel(gateway: Gateway, edge_nvr: RunningSim) -> None:
    body = {"device": edge_nvr.device(), "recordingToken": "rec_ch02", "start": "2026-10-24T08:00:00+07:00"}
    res = gateway.post("/v1/devices/recordings/replay-uri", body)
    assert res.status_code == 200, res.text
    assert res.json()["uri"] == "rtsp://camera-sim:8554/sim-24?starttime=20261024T010000Z"


def test_replay_uri_errors(gateway: Gateway, edge_nvr: RunningSim, sim_fixed: RunningSim) -> None:
    unsupported = {"device": sim_fixed.device(), "recordingToken": "rec_1", "start": "2026-10-24T01:00:00Z"}
    res = gateway.post("/v1/devices/recordings/replay-uri", unsupported)
    assert res.status_code == 424
    assert res.json() == {"code": "NOT_SUPPORTED", "detail": UNSUPPORTED_REASON}
    unknown = {"device": edge_nvr.device(), "recordingToken": "rec_ch09", "start": "2026-10-24T01:00:00Z"}
    res = gateway.post("/v1/devices/recordings/replay-uri", unknown)
    assert res.status_code == 424
    assert res.json()["code"] == "DEVICE_ERROR"


def test_search_validates_window(gateway: Gateway, edge_cam: RunningSim) -> None:
    now = datetime.now(UTC).isoformat()
    res = gateway.post("/v1/devices/recordings/search", {"device": edge_cam.device(), "from": now, "to": now})
    assert res.status_code == 422
    long = {"from": "2026-01-01T00:00:00Z", "to": "2026-03-01T00:00:00Z"}
    res = gateway.post("/v1/devices/recordings/search", {"device": edge_cam.device(), **long})
    assert res.status_code == 422


def test_search_wrong_password_is_auth_failed(gateway: Gateway, edge_nvr: RunningSim) -> None:
    res = gateway.post("/v1/devices/recordings/search", {"device": edge_nvr.device(password="sai"), **_window()})
    assert res.status_code == 424
    assert res.json()["code"] == "AUTH_FAILED"


def test_search_and_replay_through_proxy(gateway: Gateway, edge_nvr: RunningSim) -> None:
    socks = Socks5Server({"nvr-edge.site-lan": "127.0.0.1"}).start()
    try:
        device = {**edge_nvr.device(), "host": "nvr-edge.site-lan", "proxy": socks.url}
        body = _search(gateway, device, videoSourceToken="VideoSource_1")
        assert [r["recordingToken"] for r in body["recordings"]] == ["rec_ch01"]
        res = gateway.post(
            "/v1/devices/recordings/replay-uri",
            {"device": device, "recordingToken": "rec_ch01", "start": "2026-10-24T01:00:00Z"},
        )
        assert res.status_code == 200, res.text
        assert {h for h, _ in socks.requests} == {"nvr-edge.site-lan"}
    finally:
        socks.stop()


# ---- quy tắc dựng khoảng thời gian ---------------------------------------------------------------------

T0 = datetime(2026, 10, 24, 0, 0, tzinfo=UTC)


def _at(minutes: int) -> datetime:
    return T0 + timedelta(minutes=minutes)


def _event(minutes: int, present: bool, start_state: bool = False) -> TrackStateEvent:
    return TrackStateEvent("rec", "VIDEO001", _at(minutes), present, start_state)


def test_ranges_without_events_cover_bounds() -> None:
    assert build_ranges(_at(0), _at(60), []) == [(_at(0), _at(60))]
    assert build_ranges(_at(60), _at(60), []) == []


def test_ranges_follow_start_state_and_gaps() -> None:
    events = [_event(0, True, start_state=True), _event(20, False), _event(30, True), _event(50, False)]
    assert build_ranges(_at(0), _at(60), events) == [(_at(0), _at(20)), (_at(30), _at(50))]


def test_ranges_infer_initial_state_from_first_event() -> None:
    # Không có sự kiện trạng thái đầu: sự kiện đầu là "mất dữ liệu" → trước đó đang có dữ liệu
    assert build_ranges(_at(0), _at(60), [_event(10, False), _event(40, True)]) == [
        (_at(0), _at(10)),
        (_at(40), _at(60)),
    ]
    assert build_ranges(_at(0), _at(60), [_event(10, True)]) == [(_at(10), _at(60))]


def test_ranges_are_clipped_to_bounds() -> None:
    events = [_event(-30, True), _event(70, False)]
    assert build_ranges(_at(0), _at(60), events) == [(_at(0), _at(60))]


@pytest.mark.parametrize(
    ("uri", "expected"),
    [
        ("rtsp://nvr/Streaming/tracks/101", "rtsp://nvr/Streaming/tracks/101?starttime=20261024T013000Z"),
        ("rtsp://u:p@nvr:554/x", "rtsp://u:p@nvr:554/x?starttime=20261024T013000Z"),
        ("rtsp://nvr/cam/playback?channel=1&subtype=0", "rtsp://nvr/cam/playback?channel=1&subtype=0"),
    ],
)
def test_with_start_time(uri: str, expected: str) -> None:
    assert with_start_time(uri, _at(90)) == expected


def test_match_source() -> None:
    known = ["VideoSource_1", "VideoSource_2"]
    assert match_source("VideoSource_2", known) == "VideoSource_2"
    assert match_source("urn:nvr:source/VideoSource_1", known) == "VideoSource_1"
    assert match_source("rtsp://cam/1", known) is None
    assert match_source("vs0", []) == "vs0"
    assert match_source(None, known) is None


def test_simulator_gap_pattern() -> None:
    now = datetime(2026, 10, 25, 10, 15, tzinfo=UTC)
    assert data_intervals(now, 24) == [
        (datetime(2026, 10, 24, 10, 15, tzinfo=UTC), datetime(2026, 10, 25, 2, 0, tzinfo=UTC)),
        (datetime(2026, 10, 25, 2, 30, tzinfo=UTC), now),
    ]
    inside_gap = datetime(2026, 10, 25, 2, 10, tzinfo=UTC)
    assert data_intervals(inside_gap, 1) == [(datetime(2026, 10, 25, 1, 10, tzinfo=UTC), _gap(25))]


def _gap(day: int) -> datetime:
    return datetime(2026, 10, day, 2, 0, tzinfo=UTC)
