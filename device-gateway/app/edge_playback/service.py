"""Phát lại bản ghi lưu trên thiết bị (US-22.1, ONVIF Profile G: Search + Replay, Recording khi cần).

Tìm bản ghi (``POST /v1/devices/recordings/search``):

- Thiết bị thiếu dịch vụ Search hoặc Replay (không phải Profile G), hoặc trả ActionNotSupported cho
  FindRecordings → ``supported = false`` kèm lý do, không phải lỗi 424.
- Danh sách bản ghi: FindRecordings + GetRecordingSearchResults. Mỗi bản ghi lấy track video (không có thì track
  đầu tiên); biên dữ liệu là ``DataFrom..DataTo`` của track (thiếu thì ``EarliestRecording..LatestRecording``),
  cắt theo cửa sổ [from, to].
- Khoảng trống: FindEvents ``tns1:RecordingHistory/Track/State`` (``IsDataPresent``, kèm trạng thái đầu cửa sổ).
  Thiết bị không hỗ trợ FindEvents thì trả nguyên biên dữ liệu (không thấy khoảng trống).
- Nguồn video của bản ghi: ``Source/SourceId`` trùng (hoặc kết thúc bằng) token nguồn video của Media; không khớp
  thì tra job ghi (GetRecordingJobs → profile → nguồn video); thiết bị chỉ có một nguồn thì gán nguồn đó.
  Lọc theo ``videoSourceToken``: nếu không bản ghi nào xác định được nguồn thì trả mọi bản ghi (không ẩn dữ liệu).
- Đồng hồ thiết bị lệch ≥ 60 giây so với Gateway thì cửa sổ được dịch sang giờ thiết bị và kết quả dịch ngược
  lại, để dòng thời gian khớp giờ thật (lệch nhỏ hơn coi như nhiễu đo).

URI phát lại (``POST /v1/devices/recordings/replay-uri``): GetReplayUri (RTP-Unicast/RTSP). ONVIF chuẩn tua bằng
header RTSP ``Range: clock=...`` trong lệnh PLAY, nhưng MediaMTX/ffmpeg kéo luồng nguồn không gửi được header đó.
Vì vậy khi URI chưa có query, Gateway gắn thêm ``?starttime=YYYYMMDDTHHMMSSZ`` (giờ UTC, kiểu Hikvision
``/Streaming/tracks/101?starttime=...``). Hãng khác bỏ qua tham số lạ nên vô hại; URI đã có query (hãng tự mã hóa
thời điểm, ví dụ Dahua ``playback?channel=1&starttime=...``) được giữ nguyên.
"""

from __future__ import annotations

import logging
from datetime import UTC, datetime, timedelta
from urllib.parse import urlsplit, urlunsplit

from app.events.normalize import format_instant
from app.models import EdgeRecording, RecordingRange, RecordingSearchResult
from app.onvif.client import OnvifClient, RecordingData, TrackStateEvent
from app.onvif.errors import DeviceError, ErrorCode

log = logging.getLogger(__name__)

UNSUPPORTED_REASON = "Thiết bị không hỗ trợ ONVIF Profile G (lưu trữ trên thiết bị)"
_FATAL = (ErrorCode.UNREACHABLE, ErrorCode.AUTH_FAILED)
# Lệch đồng hồ nhỏ hơn ngưỡng này là sai số đo (thời gian khứ hồi), không bù
_MIN_CLOCK_SKEW = timedelta(seconds=60)

Interval = tuple[datetime, datetime]


def supports_playback(client: OnvifClient) -> bool:
    return client.has_service("search") and client.has_service("replay")


def clock_skew(client: OnvifClient) -> timedelta:
    """Giờ thiết bị trừ giờ Gateway, làm tròn giây; ``0`` khi lệch dưới 60 giây."""
    offset = client.clock_offset
    if abs(offset) < _MIN_CLOCK_SKEW:
        return timedelta(0)
    return timedelta(seconds=round(offset.total_seconds()))


def build_ranges(lo: datetime, hi: datetime, events: list[TrackStateEvent]) -> list[Interval]:
    """Các khoảng có dữ liệu trong [lo, hi] từ sự kiện có/mất dữ liệu; không có sự kiện thì coi là liên tục.

    Trạng thái tại ``lo`` lấy từ sự kiện cuối cùng không muộn hơn ``lo`` (kể cả sự kiện trạng thái đầu); không có
    thì suy từ sự kiện đầu tiên: sự kiện đầu là "mất dữ liệu" nghĩa là trước đó đang có dữ liệu.
    """
    if lo >= hi:
        return []
    if not events:
        return [(lo, hi)]
    ordered = sorted(events, key=lambda e: (e.time, not e.start_state))
    before = [e for e in ordered if e.time <= lo or e.start_state]
    present = before[-1].present if before else not ordered[0].present
    ranges: list[Interval] = []
    current = lo if present else None
    for event in ordered:
        if event.time <= lo or event.start_state:
            continue
        if event.time >= hi:
            break
        if event.present and current is None:
            current = event.time
        elif not event.present and current is not None:
            if event.time > current:
                ranges.append((current, event.time))
            current = None
    if current is not None and hi > current:
        ranges.append((current, hi))
    return ranges


def match_source(source_id: str | None, known: list[str]) -> str | None:
    """Token nguồn video ứng với ``SourceId`` của bản ghi; không biết danh sách nguồn thì dùng nguyên ``SourceId``."""
    if not source_id:
        return None
    if not known:
        return source_id
    return next(
        (t for t in known if source_id == t or source_id.endswith(("/" + t, ":" + t, "=" + t, "#" + t))),
        None,
    )


async def _map_sources(client: OnvifClient, recordings: list[RecordingData]) -> dict[str, str | None]:
    known: list[str] = []
    if client.has_service("media"):
        try:
            known = await client.get_video_sources()
        except DeviceError as e:
            if e.code in _FATAL:
                raise
            log.info("Không đọc được nguồn video của %s: %s", client.target.label, e.detail)
    mapping = {r.token: match_source(r.source_id, known) for r in recordings}
    if any(v is None for v in mapping.values()) and client.has_service("recording") and client.has_service("media"):
        try:
            jobs = await client.get_recording_job_sources()
            profiles = {p.token: p.video_source_token for p in await client.get_profiles()}
            for recording, profile in jobs:
                if mapping.get(recording, "") is None and profiles.get(profile):
                    mapping[recording] = profiles[profile]
        except DeviceError as e:
            if e.code in _FATAL:
                raise
            log.info("Không đọc được job ghi của %s: %s", client.target.label, e.detail)
    if len(known) == 1:
        mapping = {k: v or known[0] for k, v in mapping.items()}
    return mapping


def _bounds(recording: RecordingData) -> tuple[str | None, datetime | None, datetime | None]:
    track = recording.video_track or (recording.tracks[0] if recording.tracks else None)
    data_from = track.data_from if track is not None and track.data_from else recording.earliest
    data_to = track.data_to if track is not None and track.data_to else recording.latest
    return (track.token or None) if track is not None else None, data_from, data_to


async def search_recordings(
    client: OnvifClient, start: datetime, end: datetime, video_source_token: str | None
) -> RecordingSearchResult:
    if not supports_playback(client):
        return RecordingSearchResult(supported=False, reason=UNSUPPORTED_REASON)
    try:
        recordings = await client.find_recordings()
    except DeviceError as e:
        if e.code != ErrorCode.NOT_SUPPORTED:
            raise
        log.info("Thiết bị %s khai báo Search nhưng không hỗ trợ FindRecordings: %s", client.target.label, e.detail)
        return RecordingSearchResult(supported=False, reason=UNSUPPORTED_REASON)

    sources = await _map_sources(client, recordings)
    selected = recordings
    if video_source_token:
        selected = [r for r in recordings if sources.get(r.token) == video_source_token]
        if not selected and all(v is None for v in sources.values()):
            selected = recordings

    skew = clock_skew(client)
    window_start, window_end = start + skew, end + skew
    events: list[TrackStateEvent] = []
    if selected:
        try:
            events = await client.find_track_state_events(window_start, window_end, [r.token for r in selected])
        except DeviceError as e:
            if e.code in _FATAL:
                raise
            log.info("Thiết bị %s không tìm được sự kiện bản ghi: %s", client.target.label, e.detail)

    result = []
    for recording in selected:
        track_token, data_from, data_to = _bounds(recording)
        own = [
            e
            for e in events
            if e.recording_token == recording.token and (track_token is None or e.track_token in (None, track_token))
        ]
        ranges: list[Interval] = []
        if data_from is not None or data_to is not None or own:
            lo = max(window_start, data_from) if data_from is not None else window_start
            hi = min(window_end, data_to) if data_to is not None else window_end
            ranges = build_ranges(lo, hi, own)
        result.append(
            EdgeRecording(
                recording_token=recording.token,
                video_source_token=sources.get(recording.token),
                track_token=track_token,
                ranges=[
                    RecordingRange(start=format_instant(a - skew), end=format_instant(b - skew)) for a, b in ranges
                ],
            )
        )
    return RecordingSearchResult(supported=True, recordings=result)


def with_start_time(uri: str, start: datetime) -> str:
    """Gắn ``starttime=YYYYMMDDTHHMMSSZ`` (UTC) khi URI chưa có query; URI đã có query giữ nguyên."""
    parts = urlsplit(uri)
    if parts.query:
        return uri
    stamp = start.astimezone(UTC).strftime("%Y%m%dT%H%M%SZ")
    return urlunsplit(parts._replace(query=f"starttime={stamp}"))


async def replay_uri(client: OnvifClient, recording_token: str, start: datetime) -> str:
    if not supports_playback(client):
        raise DeviceError(ErrorCode.NOT_SUPPORTED, UNSUPPORTED_REASON)
    uri = await client.get_replay_uri(recording_token)
    if not uri:
        raise DeviceError(ErrorCode.DEVICE_ERROR, "Thiết bị không trả URI phát lại")
    return with_start_time(uri, start + clock_skew(client))
