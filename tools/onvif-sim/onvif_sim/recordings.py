"""Ghi hình trên thiết bị (ONVIF Profile G rút gọn, US-22.1): dịch vụ Recording, Search và Replay.

Mỗi bản ghi phủ khoảng "bây giờ − ``SIM_EDGE_HOURS``" tới "bây giờ" (theo đồng hồ giả lập, cửa sổ trượt),
trừ khoảng trống cố định 02:00–02:30 UTC mỗi ngày để dòng thời gian có chỗ mất dữ liệu.

- Camera đơn (``SIM_EDGE_RECORDING=true``): một bản ghi ``rec_1`` của nguồn ``vs0``.
- Đầu ghi: mỗi kênh có tín hiệu một bản ghi ``rec_chNN`` của nguồn ``VideoSource_N``.

Tìm kiếm trả hết kết quả trong một lần (``SearchState`` = ``Completed``). Sự kiện của FindEvents là
``tns1:RecordingHistory/Track/State`` với ``IsDataPresent`` tại mỗi điểm bắt đầu/kết thúc dữ liệu.
GetReplayUri trả nguyên URI RTSP luồng chính của kênh (máy chủ RTSP giả lập phát trực tiếp, không tua
được; thời điểm bắt đầu do Gateway gắn thêm vào URI chỉ có tác dụng với thiết bị thật).
"""

from __future__ import annotations

import uuid
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, time, timedelta
from xml.etree.ElementTree import Element  # noqa: S405 - chỉ dùng kiểu

from onvif_sim.soap import SoapFault, attr, esc, invalid_arg, iso, parse_datetime, txt
from onvif_sim.state import SimState

Handler = Callable[[Element], str]

GAP_START = time(2, 0)
GAP_LENGTH = timedelta(minutes=30)
TRACK_TOKEN = "VIDEO001"
TRACK_STATE_TOPIC = "tns1:RecordingHistory/Track/State"
_MAX_SEARCHES = 64


@dataclass(frozen=True)
class EdgeRecording:
    token: str
    source_token: str
    name: str
    rtsp: str


def data_intervals(now: datetime, hours: float) -> list[tuple[datetime, datetime]]:
    """Các khoảng có dữ liệu trong [now − hours, now], đã trừ khoảng trống 02:00–02:30 UTC mỗi ngày."""
    start = (now - timedelta(hours=hours)).replace(microsecond=0)
    end = now.replace(microsecond=0)
    result: list[tuple[datetime, datetime]] = []
    cursor = start
    day = datetime.combine(start.date(), GAP_START, tzinfo=start.tzinfo)
    while cursor < end:
        gap_start, gap_end = day, day + GAP_LENGTH
        day += timedelta(days=1)
        if gap_end <= cursor:
            continue
        if gap_start >= end:
            break
        if gap_start > cursor:
            result.append((cursor, gap_start))
        cursor = max(cursor, gap_end)
    if cursor < end:
        result.append((cursor, end))
    return result


def present_at(intervals: list[tuple[datetime, datetime]], moment: datetime) -> bool:
    return any(a <= moment < b for a, b in intervals)


class EdgeRecordingService:
    """Xử lý thao tác Recording/Search/Replay trên trạng thái của bộ giả lập."""

    def __init__(self, state: SimState) -> None:
        self.state = state
        config = state.config
        self.hours = config.edge_hours
        if config.is_recorder:
            self.recordings = [
                EdgeRecording(f"rec_ch{n:02d}", f"VideoSource_{n}", config.channel_name(n), config.channel_rtsp(n)[0])
                for n in range(1, config.channels + 1)
                if config.channel_has_signal(n)
            ]
        else:
            self.recordings = [EdgeRecording("rec_1", "vs0", config.name, config.rtsp_main)]
        # SearchToken → (loại tìm kiếm, nội dung kết quả đã dựng sẵn)
        self._searches: dict[str, tuple[str, str]] = {}

    @property
    def search(self) -> dict[str, Handler]:
        return {
            "GetServiceCapabilities": self.get_search_capabilities,
            "GetRecordingSummary": self.get_recording_summary,
            "GetRecordingInformation": self.get_recording_information,
            "FindRecordings": self.find_recordings,
            "GetRecordingSearchResults": self.get_recording_search_results,
            "FindEvents": self.find_events,
            "GetEventSearchResults": self.get_event_search_results,
            "EndSearch": self.end_search,
        }

    @property
    def recording(self) -> dict[str, Handler]:
        return {
            "GetServiceCapabilities": self.get_recording_capabilities,
            "GetRecordings": self.get_recordings,
            "GetRecordingJobs": self.get_recording_jobs,
        }

    @property
    def replay(self) -> dict[str, Handler]:
        return {
            "GetServiceCapabilities": self.get_replay_capabilities,
            "GetReplayUri": self.get_replay_uri,
        }

    # ---- tiện ích -------------------------------------------------------------------------------------

    def _intervals(self) -> list[tuple[datetime, datetime]]:
        return data_intervals(self.state.now(), self.hours)

    def _require(self, token: str | None) -> EdgeRecording:
        found = next((r for r in self.recordings if r.token == token), None)
        if found is None:
            raise SoapFault("Sender", "ter:NoRecording", "Bản ghi không tồn tại")
        return found

    def _new_search(self, kind: str, results: str) -> str:
        token = uuid.uuid4().hex
        self._searches[token] = (kind, results)
        while len(self._searches) > _MAX_SEARCHES:
            self._searches.pop(next(iter(self._searches)))
        return token

    def _take_search(self, req: Element, kind: str) -> str:
        token = txt(req, "{*}SearchToken")
        found = self._searches.get(token or "")
        if found is None or found[0] != kind:
            raise SoapFault("Sender", "ter:InvalidToken", "SearchToken không hợp lệ")
        return found[1]

    def _information_xml(self, rec: EdgeRecording) -> str:
        intervals = self._intervals()
        first, last = intervals[0][0], intervals[-1][1]
        return (
            f"<tt:RecordingToken>{esc(rec.token)}</tt:RecordingToken>"
            f"<tt:Source><tt:SourceId>{esc(rec.source_token)}</tt:SourceId><tt:Name>{esc(rec.name)}</tt:Name>"
            "<tt:Location>CamHub Sim</tt:Location><tt:Description>Ghi liên tục trên thiết bị</tt:Description>"
            f"<tt:Address>{esc(rec.rtsp)}</tt:Address></tt:Source>"
            f"<tt:EarliestRecording>{iso(first)}</tt:EarliestRecording>"
            f"<tt:LatestRecording>{iso(last)}</tt:LatestRecording><tt:Content>{esc(rec.name)}</tt:Content>"
            f"<tt:Track><tt:TrackToken>{TRACK_TOKEN}</tt:TrackToken><tt:TrackType>Video</tt:TrackType>"
            f"<tt:Description>Video</tt:Description><tt:DataFrom>{iso(first)}</tt:DataFrom>"
            f"<tt:DataTo>{iso(last)}</tt:DataTo></tt:Track>"
            "<tt:RecordingStatus>Recording</tt:RecordingStatus>"
        )

    # ---- Search ---------------------------------------------------------------------------------------

    def get_search_capabilities(self, _: Element) -> str:
        return (
            '<tse:GetServiceCapabilitiesResponse><tse:Capabilities MetadataSearch="false" '
            'GeneralStartEvents="true"/></tse:GetServiceCapabilitiesResponse>'
        )

    def get_recording_summary(self, _: Element) -> str:
        intervals = self._intervals()
        return (
            "<tse:GetRecordingSummaryResponse><tse:Summary>"
            f"<tt:DataFrom>{iso(intervals[0][0])}</tt:DataFrom><tt:DataUntil>{iso(intervals[-1][1])}</tt:DataUntil>"
            f"<tt:NumberRecordings>{len(self.recordings)}</tt:NumberRecordings>"
            "</tse:Summary></tse:GetRecordingSummaryResponse>"
        )

    def get_recording_information(self, req: Element) -> str:
        rec = self._require(txt(req, "{*}RecordingToken"))
        return (
            "<tse:GetRecordingInformationResponse><tse:RecordingInformation>"
            f"{self._information_xml(rec)}</tse:RecordingInformation></tse:GetRecordingInformationResponse>"
        )

    def find_recordings(self, _: Element) -> str:
        items = "".join(
            f"<tt:RecordingInformation>{self._information_xml(r)}</tt:RecordingInformation>" for r in self.recordings
        )
        token = self._new_search("recordings", items)
        return f"<tse:FindRecordingsResponse><tse:SearchToken>{token}</tse:SearchToken></tse:FindRecordingsResponse>"

    def get_recording_search_results(self, req: Element) -> str:
        items = self._take_search(req, "recordings")
        return (
            "<tse:GetRecordingSearchResultsResponse><tse:ResultList><tt:SearchState>Completed</tt:SearchState>"
            f"{items}</tse:ResultList></tse:GetRecordingSearchResultsResponse>"
        )

    def _event_xml(self, rec: EdgeRecording, moment: datetime, present: bool, start_state: bool) -> str:
        stamp = iso(moment)
        return (
            f"<tt:Result><tt:RecordingToken>{esc(rec.token)}</tt:RecordingToken>"
            f"<tt:TrackToken>{TRACK_TOKEN}</tt:TrackToken><tt:Time>{stamp}</tt:Time>"
            f"<tt:Event><wsnt:Topic Dialect={attr('http://www.onvif.org/ver10/tev/topicExpression/ConcreteSet')}>"
            f"{TRACK_STATE_TOPIC}</wsnt:Topic><wsnt:Message>"
            f'<tt:Message UtcTime="{stamp}" PropertyOperation="{"Initialized" if start_state else "Changed"}">'
            f'<tt:Source><tt:SimpleItem Name="RecordingToken" Value={attr(rec.token)}/>'
            f'<tt:SimpleItem Name="Track" Value="{TRACK_TOKEN}"/></tt:Source>'
            f'<tt:Data><tt:SimpleItem Name="IsDataPresent" Value="{str(present).lower()}"/></tt:Data>'
            "</tt:Message></wsnt:Message></tt:Event>"
            f"<tt:StartStateEvent>{str(start_state).lower()}</tt:StartStateEvent></tt:Result>"
        )

    def find_events(self, req: Element) -> str:
        start = parse_datetime(txt(req, "{*}StartPoint", "") or "")
        if start is None:
            raise invalid_arg("StartPoint không hợp lệ")
        end_raw = txt(req, "{*}EndPoint")
        end = parse_datetime(end_raw) if end_raw else self.state.now()
        if end is None:
            raise invalid_arg("EndPoint không hợp lệ")
        start, end = min(start, end), max(start, end)
        included = [e.text.strip() for e in req.iterfind("{*}Scope/{*}IncludedRecordings") if e.text]
        include_start = (txt(req, "{*}IncludeStartState", "false") or "false").lower() == "true"
        targets = [r for r in self.recordings if not included or r.token in included]
        intervals = self._intervals()
        items: list[str] = []
        for rec in targets:
            if include_start:
                items.append(self._event_xml(rec, start, present_at(intervals, start), True))
            for a, b in intervals:
                if start < a <= end:
                    items.append(self._event_xml(rec, a, True, False))
                if start < b <= end and b < intervals[-1][1]:
                    items.append(self._event_xml(rec, b, False, False))
        token = self._new_search("events", "".join(items))
        return f"<tse:FindEventsResponse><tse:SearchToken>{token}</tse:SearchToken></tse:FindEventsResponse>"

    def get_event_search_results(self, req: Element) -> str:
        items = self._take_search(req, "events")
        return (
            "<tse:GetEventSearchResultsResponse><tse:ResultList><tt:SearchState>Completed</tt:SearchState>"
            f"{items}</tse:ResultList></tse:GetEventSearchResultsResponse>"
        )

    def end_search(self, req: Element) -> str:
        self._searches.pop(txt(req, "{*}SearchToken", "") or "", None)
        return f"<tse:EndSearchResponse><tse:Endpoint>{iso(self.state.now())}</tse:Endpoint></tse:EndSearchResponse>"

    # ---- Recording ------------------------------------------------------------------------------------

    def get_recording_capabilities(self, _: Element) -> str:
        return (
            "<trc:GetServiceCapabilitiesResponse>"
            f'<trc:Capabilities DynamicRecordings="false" MaxRecordings="{len(self.recordings)}"/>'
            "</trc:GetServiceCapabilitiesResponse>"
        )

    def get_recordings(self, _: Element) -> str:
        items = "".join(
            f"<trc:RecordingItem><tt:RecordingToken>{esc(r.token)}</tt:RecordingToken><tt:Configuration>"
            f"<tt:Source><tt:SourceId>{esc(r.source_token)}</tt:SourceId><tt:Name>{esc(r.name)}</tt:Name>"
            "<tt:Location/><tt:Description/><tt:Address/></tt:Source><tt:Content/>"
            "<tt:MaximumRetentionTime>PT0S</tt:MaximumRetentionTime></tt:Configuration>"
            f"<tt:Tracks><tt:Track><tt:TrackToken>{TRACK_TOKEN}</tt:TrackToken><tt:Configuration>"
            "<tt:TrackType>Video</tt:TrackType><tt:Description/></tt:Configuration></tt:Track></tt:Tracks>"
            "</trc:RecordingItem>"
            for r in self.recordings
        )
        return f"<trc:GetRecordingsResponse>{items}</trc:GetRecordingsResponse>"

    def get_recording_jobs(self, _: Element) -> str:
        items = []
        for index, rec in enumerate(self.recordings, start=1):
            profile = next((p for p in self.state.profiles if p.source_token == rec.source_token), None)
            source = (
                f'<tt:Source><tt:SourceToken Type="http://www.onvif.org/ver10/schema/Profile">'
                f"<tt:Token>{esc(profile.token)}</tt:Token></tt:SourceToken></tt:Source>"
                if profile
                else ""
            )
            items.append(
                f"<trc:JobItem><tt:JobToken>job_{index}</tt:JobToken><tt:JobConfiguration>"
                f"<tt:RecordingToken>{esc(rec.token)}</tt:RecordingToken><tt:Mode>Active</tt:Mode>"
                f"<tt:Priority>1</tt:Priority>{source}</tt:JobConfiguration></trc:JobItem>"
            )
        return f"<trc:GetRecordingJobsResponse>{''.join(items)}</trc:GetRecordingJobsResponse>"

    # ---- Replay ---------------------------------------------------------------------------------------

    def get_replay_capabilities(self, _: Element) -> str:
        return (
            '<trp:GetServiceCapabilitiesResponse><trp:Capabilities ReversePlayback="false" '
            'RTP_RTSP_TCP="true"/></trp:GetServiceCapabilitiesResponse>'
        )

    def get_replay_uri(self, req: Element) -> str:
        rec = self._require(txt(req, "{*}RecordingToken"))
        protocol = txt(req, "{*}StreamSetup/{*}Transport/{*}Protocol", "RTSP")
        if protocol not in ("RTSP", "UDP", "TCP"):
            raise invalid_arg("Giao thức không hỗ trợ")
        return f"<trp:GetReplayUriResponse><trp:Uri>{esc(rec.rtsp)}</trp:Uri></trp:GetReplayUriResponse>"
