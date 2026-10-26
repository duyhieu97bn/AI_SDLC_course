"""Client ONVIF bất đồng bộ: Device, Media (ver10), Media2 (vùng che), PTZ (ver20), Imaging (ver20),
Analytics (ver20, quy tắc/mô-đun phát hiện chuyển động), Events (PullPoint) và Search/Recording/Replay
(Profile G, phát lại bản ghi trên thiết bị).

Mỗi lệnh từ Core tạo một ``OnvifClient`` ngắn hạn dùng chung ``httpx.AsyncClient`` của ứng dụng.
``connect()`` đọc đồng hồ thiết bị (để bù lệch cho ``wsu:Created``) và danh sách dịch vụ; kết quả
được nhớ trong ``SessionCache`` theo (proxy, host, port) để lệnh PTZ liên tiếp không phải hỏi lại.

Thiết bị ở site xa (TS-06.3): ``DeviceTarget.proxy`` khác ``None`` thì ``http`` truyền vào là client SOCKS5
của tunnel (xem ``app.transport.tunnel_router``); mọi URL thiết bị, kể cả XAddr LAN, đều đi qua client đó.
"""

from __future__ import annotations

import ipaddress
import logging
import time
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from typing import Final
from urllib.parse import urlsplit, urlunsplit
from xml.etree.ElementTree import Element  # noqa: S405 - chỉ dùng kiểu

import httpx

from app.metrics import DEVICE_CALL_SECONDS, DEVICE_CALLS
from app.models import (
    EncoderOptions,
    ImagingOptions,
    ImagingSettings,
    ImagingUpdate,
    IrMode,
    Preset,
    Range,
    Resolution,
)
from app.onvif import soap
from app.onvif.errors import DeviceError, ErrorCode
from app.onvif.soap import attr, bool_text, esc, float_of, int_of, local_name, text_of

log = logging.getLogger(__name__)

SERVICE_NAMESPACES: Final[dict[str, str]] = {
    "http://www.onvif.org/ver10/device/wsdl": "device",
    "http://www.onvif.org/ver10/media/wsdl": "media",
    "http://www.onvif.org/ver20/media/wsdl": "media2",
    "http://www.onvif.org/ver20/ptz/wsdl": "ptz",
    "http://www.onvif.org/ver20/imaging/wsdl": "imaging",
    "http://www.onvif.org/ver10/events/wsdl": "events",
    "http://www.onvif.org/ver10/recording/wsdl": "recording",
    "http://www.onvif.org/ver10/search/wsdl": "search",
    "http://www.onvif.org/ver10/replay/wsdl": "replay",
    "http://www.onvif.org/ver10/deviceIO/wsdl": "deviceio",
    "http://www.onvif.org/ver20/analytics/wsdl": "analytics",
}

# IrCutFilter=ON nghĩa là kính lọc hồng ngoại đang chắn (ảnh màu ban ngày), OFF là chế độ đêm
IR_TO_ONVIF: Final[dict[str, str]] = {"AUTO": "AUTO", "DAY": "ON", "NIGHT": "OFF"}
ONVIF_TO_IR: Final[dict[str, IrMode]] = {"AUTO": "AUTO", "ON": "DAY", "OFF": "NIGHT"}

_EVENTS_ACTION = "http://www.onvif.org/ver10/events/wsdl"
_WSN_ACTION = "http://docs.oasis-open.org/wsn/bw-2/SubscriptionManager"
_SNAPSHOT_MAX_BYTES = 10 * 1024 * 1024
_MOTION_TOPIC_HINTS = ("motion", "cellmotiondetector")
# Tìm kiếm Profile G: mỗi trang tối đa 100 kết quả, tối đa 50 trang (đủ cho đầu ghi 64 kênh, cửa sổ vài tuần)
_SEARCH_PAGE = 100
_SEARCH_MAX_PAGES = 50
_TRACK_STATE_TOPIC = "tns1:RecordingHistory/Track/State"
_CONCRETE_SET = "http://www.onvif.org/ver10/tev/topicExpression/ConcreteSet"
_TOPICS_NS = "http://www.onvif.org/ver10/topics"


@dataclass(frozen=True)
class DeviceTarget:
    host: str
    port: int
    username: str
    password: str = field(repr=False)
    # Proxy SOCKS5 của tunnel site (TS-06.3); ``None`` là gọi thẳng
    proxy: str | None = None

    @property
    def netloc(self) -> str:
        host = f"[{self.host}]" if ":" in self.host else self.host
        return f"{host}:{self.port}"

    @property
    def label(self) -> str:
        """``host:port`` kèm tunnel (nếu có), dùng trong log và thông báo lỗi."""
        return f"{self.netloc} qua {self.proxy}" if self.proxy else self.netloc

    @property
    def scheme(self) -> str:
        return "https" if self.port == 443 else "http"

    @property
    def device_url(self) -> str:
        return f"{self.scheme}://{self.netloc}/onvif/device_service"


@dataclass
class DeviceSession:
    clock_offset: timedelta
    services: dict[str, str]
    service_caps: dict[str, dict[str, str]]
    expires_at: float


SessionKey = tuple[str, str, int]


class SessionCache:
    """Nhớ độ lệch đồng hồ và XAddr dịch vụ theo (proxy, host, port), không chứa tài khoản.

    Proxy nằm trong khóa vì hai site khác nhau có thể dùng cùng địa chỉ LAN (ví dụ ``192.168.1.64``).
    """

    def __init__(self, ttl_seconds: float) -> None:
        self._ttl = ttl_seconds
        self._items: dict[SessionKey, DeviceSession] = {}

    def get(self, key: SessionKey) -> DeviceSession | None:
        item = self._items.get(key)
        if item is None or item.expires_at < time.monotonic():
            self._items.pop(key, None)
            return None
        return item

    def put(self, key: SessionKey, session: DeviceSession) -> None:
        session.expires_at = time.monotonic() + self._ttl
        self._items[key] = session

    def invalidate(self, key: SessionKey) -> None:
        self._items.pop(key, None)


@dataclass(frozen=True)
class DeviceInformation:
    manufacturer: str | None
    model: str | None
    firmware: str | None
    serial_number: str | None
    hardware_id: str | None


@dataclass(frozen=True)
class DeviceClock:
    utc: datetime
    date_time_type: str | None
    daylight_savings: bool
    timezone: str | None


@dataclass(frozen=True)
class NetworkInterface:
    token: str
    enabled: bool
    mac_address: str | None
    ipv4_address: str | None
    from_dhcp: bool
    prefix_length: int | None = None


@dataclass(frozen=True)
class EncoderData:
    token: str
    name: str
    codec: str | None
    width: int
    height: int
    fps: int
    bitrate_kbps: int
    gov_length: int | None
    quality: float | None
    h264_profile: str | None
    b_frames: bool


@dataclass(frozen=True)
class ProfileData:
    token: str
    name: str
    video_source_token: str | None
    encoder: EncoderData | None
    has_ptz: bool
    has_audio_in: bool
    has_audio_out: bool
    video_source_config_token: str | None = None
    analytics_token: str | None = None
    video_source_config_name: str | None = None


@dataclass(frozen=True)
class VideoSourceData:
    """``tt:VideoSource``; ``framerate``/``resolution`` là ``None`` khi thiết bị không khai báo."""

    token: str
    framerate: float | None
    resolution: tuple[int, int] | None


@dataclass(frozen=True)
class MaskOptionsData:
    max_masks: int | None
    max_points: int | None
    rectangle_only: bool
    types: list[str]


@dataclass(frozen=True)
class MaskData:
    """Vùng che riêng tư Media2; tọa độ theo hệ ONVIF (−1..1, trục y hướng lên)."""

    token: str
    points: list[tuple[float, float]]
    enabled: bool


@dataclass(frozen=True)
class PullPoint:
    address: str
    reference_parameters: str = ""


@dataclass(frozen=True)
class RawEvent:
    topic: str
    utc_time: str | None
    property_operation: str | None
    source: dict[str, str]
    data: dict[str, str]


@dataclass(frozen=True)
class TrackData:
    token: str
    type: str | None
    data_from: datetime | None
    data_to: datetime | None


@dataclass(frozen=True)
class RecordingData:
    """``tt:RecordingInformation`` (Search). Thời điểm theo đồng hồ thiết bị, đã chuyển sang UTC."""

    token: str
    source_id: str | None
    earliest: datetime | None
    latest: datetime | None
    tracks: list[TrackData]

    @property
    def video_track(self) -> TrackData | None:
        return next((t for t in self.tracks if (t.type or "").lower() == "video"), None)


@dataclass(frozen=True)
class TrackStateEvent:
    """Sự kiện ``tns1:RecordingHistory/Track/State`` (FindEvents): dữ liệu bắt đầu/ngừng có mặt trên track."""

    recording_token: str
    track_token: str | None
    time: datetime
    present: bool
    start_state: bool


def parse_xs_datetime(raw: str | None) -> datetime | None:
    """``xs:dateTime`` → ``datetime`` UTC; không có múi giờ thì coi là UTC."""
    if not raw:
        return None
    try:
        moment = datetime.fromisoformat(raw.strip())
    except ValueError:
        return None
    return moment.replace(tzinfo=UTC) if moment.tzinfo is None else moment.astimezone(UTC)


def format_xs_datetime(moment: datetime) -> str:
    return moment.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_recording(info: Element) -> RecordingData:
    return RecordingData(
        token=text_of(info, "{*}RecordingToken", "") or "",
        source_id=text_of(info, "{*}Source/{*}SourceId") or None,
        earliest=parse_xs_datetime(text_of(info, "{*}EarliestRecording")),
        latest=parse_xs_datetime(text_of(info, "{*}LatestRecording")),
        tracks=[
            TrackData(
                token=text_of(track, "{*}TrackToken", "") or "",
                type=text_of(track, "{*}TrackType"),
                data_from=parse_xs_datetime(text_of(track, "{*}DataFrom")),
                data_to=parse_xs_datetime(text_of(track, "{*}DataTo")),
            )
            for track in info.iterfind("{*}Track")
        ],
    )


def parse_track_state_event(result: Element) -> TrackStateEvent | None:
    """Một ``tt:FindEventResult``; ``None`` nếu không phải sự kiện có/mất dữ liệu của track."""
    recording = text_of(result, "{*}RecordingToken")
    event = result.find("{*}Event")
    if not recording or event is None:
        return None
    topic = (text_of(event, "{*}Topic", "") or "").replace(" ", "")
    msg = event.find("{*}Message/{*}Message")
    data = _simple_items(msg, "{*}Data/{*}SimpleItem") if msg is not None else {}
    raw = next((v for k, v in data.items() if k.lower() in ("isdatapresent", "state")), None)
    if raw is None or (topic and not topic.lower().endswith("track/state")):
        return None
    present = bool_text(raw)
    moment = parse_xs_datetime(text_of(result, "{*}Time")) or parse_xs_datetime(
        msg.get("UtcTime") if msg is not None else None
    )
    if present is None or moment is None:
        return None
    return TrackStateEvent(
        recording_token=recording,
        track_token=text_of(result, "{*}TrackToken"),
        time=moment,
        present=present,
        start_state=bool_text(text_of(result, "{*}StartStateEvent")) is True,
    )


def normalize_codec(raw: str | None) -> str | None:
    if raw is None:
        return None
    value = raw.strip().upper().replace(".", "")
    if value in ("H265", "HEVC"):
        return "H265"
    if value in ("H264", "AVC"):
        return "H264"
    return value or None


def detect_b_frames(config: Element) -> bool:
    """Chỉ trả ``True`` khi cấu hình nêu rõ có B-frame (phần mở rộng hãng, ví dụ ``BFrames``/``BFrameNum`` > 0).

    ONVIF ver10 không có trường B-frame; suy đoán theo H264 profile không đáng tin nên không dùng.
    """

    def positive(value: str | None) -> bool:
        if value is None:
            return False
        flag = bool_text(value)
        if flag is not None:
            return flag
        try:
            return float(value) > 0
        except ValueError:
            return False

    for node in config.iter():
        if "bframe" in local_name(node.tag).lower().replace("_", "").replace("-", "") and positive(node.text):
            return True
        for key, value in node.attrib.items():
            if "bframe" in local_name(key).lower().replace("_", "").replace("-", "") and positive(value):
                return True
    return False


def parse_encoder(config: Element) -> EncoderData:
    gov = text_of(config, "{*}H264/{*}GovLength") or text_of(config, "{*}MPEG4/{*}GovLength")
    if gov is None:
        gov_el = next((n for n in config.iter() if local_name(n.tag) == "GovLength"), None)
        gov = gov_el.text.strip() if gov_el is not None and gov_el.text else config.get("GovLength")
    try:
        gov_length = int(gov) if gov else None
    except ValueError:
        gov_length = None
    return EncoderData(
        token=config.get("token", ""),
        name=text_of(config, "{*}Name", "") or "",
        codec=normalize_codec(text_of(config, "{*}Encoding")),
        width=int_of(config, "{*}Resolution/{*}Width"),
        height=int_of(config, "{*}Resolution/{*}Height"),
        fps=int_of(config, "{*}RateControl/{*}FrameRateLimit"),
        bitrate_kbps=int_of(config, "{*}RateControl/{*}BitrateLimit"),
        gov_length=gov_length,
        quality=float_of(config, "{*}Quality"),
        h264_profile=text_of(config, "{*}H264/{*}H264Profile"),
        b_frames=detect_b_frames(config),
    )


def _range(el: Element | None, path: str) -> Range | None:
    if el is None:
        return None
    node = el.find(path)
    if node is None:
        return None
    low, high = float_of(node, "{*}Min"), float_of(node, "{*}Max")
    if low is None or high is None:
        return None
    return Range(min=low, max=high)


def parse_encoder_options(options: Element) -> EncoderOptions:
    codecs: list[str] = []
    resolutions: list[Resolution] = []
    seen: set[tuple[int, int]] = set()
    fps_range = gov_range = bitrate_range = None
    profiles: list[str] = []
    for codec in ("H264", "MPEG4", "JPEG"):
        node = options.find("{*}" + codec)
        if node is None:
            continue
        codecs.append(codec)
        for res in node.iterfind("{*}ResolutionsAvailable"):
            pair = (int_of(res, "{*}Width"), int_of(res, "{*}Height"))
            if pair not in seen and pair[0] > 0:
                seen.add(pair)
                resolutions.append(Resolution(width=pair[0], height=pair[1]))
        fps_range = fps_range or _range(node, "{*}FrameRateRange")
        gov_range = gov_range or _range(node, "{*}GovLengthRange")
        profiles += [p.text.strip() for p in node.iterfind("{*}H264ProfilesSupported") if p.text]
        bitrate_range = bitrate_range or _range(options, f"{{*}}Extension/{{*}}{codec}/{{*}}BitrateRange")
    # H.265 không có trong lược đồ ver10; nhiều hãng khai báo qua phần mở rộng
    if any(local_name(n.tag) in ("H265", "HEVC") for n in options.iter()):
        codecs.append("H265")
    return EncoderOptions(
        codecs=codecs,
        resolutions=resolutions,
        fps_range=fps_range,
        bitrate_range=bitrate_range,
        gov_length_range=gov_range,
        quality_range=_range(options, "{*}QualityRange"),
        h264_profiles=profiles,
    )


def parse_onvif_datetime(el: Element | None) -> datetime | None:
    if el is None:
        return None
    try:
        return datetime(
            int_of(el, "{*}Date/{*}Year"),
            int_of(el, "{*}Date/{*}Month"),
            int_of(el, "{*}Date/{*}Day"),
            int_of(el, "{*}Time/{*}Hour"),
            int_of(el, "{*}Time/{*}Minute"),
            int_of(el, "{*}Time/{*}Second"),
            tzinfo=UTC,
        )
    except ValueError:
        return None


def _duration(seconds: float) -> str:
    """Chuỗi thời lượng ISO 8601 cho ONVIF, ví dụ ``PT60S`` hoặc ``PT1.500S``."""
    if float(seconds).is_integer():
        return f"PT{int(seconds)}S"
    return f"PT{seconds:.3f}S"


def _num(value: float) -> str:
    return str(round(value, 4))


class OnvifClient:
    def __init__(
        self,
        target: DeviceTarget,
        http: httpx.AsyncClient,
        *,
        timeout: float = 10.0,
        cache: SessionCache | None = None,
        rewrite_xaddr: bool = True,
    ) -> None:
        self.target = target
        self._http = http
        self._timeout = timeout
        self._cache = cache
        self._rewrite = rewrite_xaddr
        self._session: DeviceSession | None = None
        self._offset = timedelta(0)

    # ---- hạ tầng gọi SOAP -----------------------------------------------------------------------------

    @property
    def _key(self) -> SessionKey:
        return (self.target.proxy or "", self.target.host, self.target.port)

    @property
    def clock_offset(self) -> timedelta:
        """Đồng hồ thiết bị trừ đồng hồ Gateway."""
        return self._offset

    def device_now(self) -> datetime:
        return datetime.now(UTC) + self._offset

    async def _call(
        self,
        url: str,
        op: str,
        body: str,
        *,
        auth: bool = True,
        action: str | None = None,
        extra_header: str = "",
        timeout: float | None = None,
    ) -> Element:
        header = extra_header
        if auth and self.target.username:
            header = soap.security_header(self.target.username, self.target.password, self.device_now()) + header
        content_type = "application/soap+xml; charset=utf-8"
        if action:
            content_type += f'; action="{action}"'
        result = "OK"
        started = time.perf_counter()
        try:
            res = await self._http.post(
                url,
                content=soap.build_envelope(body, header),
                headers={"Content-Type": content_type},
                timeout=timeout or self._timeout,
            )
            return soap.parse_response(res.status_code, res.content)
        except DeviceError as e:
            result = e.code
            raise
        except httpx.TimeoutException as e:
            result = ErrorCode.UNREACHABLE
            self._invalidate()
            raise DeviceError(ErrorCode.UNREACHABLE, f"Hết thời gian chờ thiết bị {self.target.label} ({op})") from e
        except httpx.RemoteProtocolError as e:
            result = ErrorCode.NOT_ONVIF
            raise DeviceError(ErrorCode.NOT_ONVIF, f"Thiết bị {self.target.netloc} trả HTTP không hợp lệ") from e
        except (httpx.TransportError, httpx.InvalidURL) as e:
            result = ErrorCode.UNREACHABLE
            self._invalidate()
            raise DeviceError(ErrorCode.UNREACHABLE, f"Không kết nối được thiết bị {self.target.label}") from e
        finally:
            DEVICE_CALLS.labels(op=op, result=str(result)).inc()
            DEVICE_CALL_SECONDS.labels(op=op).observe(time.perf_counter() - started)

    def _invalidate(self) -> None:
        if self._cache is not None:
            self._cache.invalidate(self._key)

    def rewrite_url(self, url: str) -> str:
        """Thay host:port khi XAddr trỏ tới địa chỉ khác địa chỉ Core dùng (camera sau NAT/chuyển cổng)."""
        if not self._rewrite:
            return url
        parts = urlsplit(url)
        if not parts.hostname or parts.hostname.lower() == self.target.host.lower():
            return url
        return urlunsplit((self.target.scheme, self.target.netloc, parts.path, parts.query, parts.fragment))

    async def connect(self) -> None:
        if self._session is not None:
            return
        cached = self._cache.get(self._key) if self._cache else None
        if cached is not None:
            self._session, self._offset = cached, cached.clock_offset
            return
        await self._sync_clock()
        services, caps = await self._load_services()
        self._session = DeviceSession(self._offset, services, caps, 0.0)
        if self._cache is not None:
            self._cache.put(self._key, self._session)

    async def _sync_clock(self) -> None:
        sent = datetime.now(UTC)
        try:
            clock = await self.get_system_date_and_time(auth=False)
        except DeviceError as e:
            if e.code != ErrorCode.AUTH_FAILED:
                if e.code in (ErrorCode.UNREACHABLE, ErrorCode.NOT_ONVIF):
                    raise
                log.warning("Thiết bị %s không trả giờ hệ thống: %s", self.target.netloc, e.detail)
                return
            sent = datetime.now(UTC)
            clock = await self.get_system_date_and_time(auth=True)
        received = datetime.now(UTC)
        self._offset = clock.utc - (sent + (received - sent) / 2)
        if abs(self._offset.total_seconds()) > 5:
            log.info("Đồng hồ thiết bị %s lệch %.1f giây, đã bù", self.target.netloc, self._offset.total_seconds())

    @property
    def services(self) -> dict[str, str]:
        return self._session.services if self._session else {}

    @property
    def service_caps(self) -> dict[str, dict[str, str]]:
        return self._session.service_caps if self._session else {}

    def has_service(self, key: str) -> bool:
        return key in self.services

    def service_url(self, key: str) -> str:
        url = self.services.get(key)
        if not url:
            raise DeviceError(ErrorCode.NOT_SUPPORTED, f"Thiết bị không có dịch vụ ONVIF '{key}'")
        return url

    async def _load_services(self) -> tuple[dict[str, str], dict[str, dict[str, str]]]:
        services = {"device": self.target.device_url}
        caps: dict[str, dict[str, str]] = {}
        try:
            body = await self._call(
                self.target.device_url,
                "GetServices",
                "<tds:GetServices><tds:IncludeCapability>true</tds:IncludeCapability></tds:GetServices>",
            )
        except DeviceError as e:
            if e.code in (ErrorCode.AUTH_FAILED, ErrorCode.UNREACHABLE, ErrorCode.NOT_ONVIF):
                raise
            return await self._load_capabilities(services)
        for svc in body.iterfind(".//{*}Service"):
            key = SERVICE_NAMESPACES.get(text_of(svc, "{*}Namespace") or "")
            xaddr = text_of(svc, "{*}XAddr")
            if key is None or not xaddr:
                continue
            if key != "device":
                services[key] = self.rewrite_url(xaddr)
            wrapper = svc.find("{*}Capabilities")
            inner = next(iter(wrapper), None) if wrapper is not None else None
            if inner is not None:
                caps[key] = {local_name(k): v for k, v in inner.attrib.items()}
        if len(services) == 1:
            return await self._load_capabilities(services)
        return services, caps

    async def _load_capabilities(self, services: dict[str, str]) -> tuple[dict[str, str], dict[str, dict[str, str]]]:
        body = await self._call(
            self.target.device_url,
            "GetCapabilities",
            "<tds:GetCapabilities><tds:Category>All</tds:Category></tds:GetCapabilities>",
        )
        root = body.find(".//{*}Capabilities")
        caps: dict[str, dict[str, str]] = {}
        paths = {
            "media": "{*}Media",
            "ptz": "{*}PTZ",
            "imaging": "{*}Imaging",
            "events": "{*}Events",
            "analytics": "{*}Analytics",
            "recording": "{*}Extension/{*}Recording",
            "search": "{*}Extension/{*}Search",
            "replay": "{*}Extension/{*}Replay",
            "deviceio": "{*}Extension/{*}DeviceIO",
        }
        for key, path in paths.items():
            xaddr = text_of(root, path + "/{*}XAddr")
            if xaddr:
                services[key] = self.rewrite_url(xaddr)
        deviceio = root.find("{*}Extension/{*}DeviceIO") if root is not None else None
        if deviceio is not None:
            caps["deviceio"] = {
                "AudioSources": text_of(deviceio, "{*}AudioSources", "0") or "0",
                "AudioOutputs": text_of(deviceio, "{*}AudioOutputs", "0") or "0",
            }
        return services, caps

    # ---- Device service -------------------------------------------------------------------------------

    async def get_system_date_and_time(self, *, auth: bool = True) -> DeviceClock:
        body = await self._call(
            self.target.device_url, "GetSystemDateAndTime", "<tds:GetSystemDateAndTime/>", auth=auth
        )
        info = body.find(".//{*}SystemDateAndTime")
        utc = parse_onvif_datetime(info.find("{*}UTCDateTime") if info is not None else None)
        if utc is None:
            # Một số thiết bị chỉ trả giờ địa phương; coi như UTC để vẫn tính được độ lệch gần đúng
            utc = parse_onvif_datetime(info.find("{*}LocalDateTime") if info is not None else None)
        if utc is None:
            raise DeviceError(ErrorCode.DEVICE_ERROR, "Thiết bị không trả giờ hệ thống hợp lệ")
        return DeviceClock(
            utc=utc,
            date_time_type=text_of(info, "{*}DateTimeType"),
            daylight_savings=bool_text(text_of(info, "{*}DaylightSavings")) or False,
            timezone=text_of(info, "{*}TimeZone/{*}TZ"),
        )

    async def get_device_information(self) -> DeviceInformation:
        body = await self._call(self.target.device_url, "GetDeviceInformation", "<tds:GetDeviceInformation/>")
        res = body.find("{*}GetDeviceInformationResponse")
        return DeviceInformation(
            manufacturer=text_of(res, "{*}Manufacturer"),
            model=text_of(res, "{*}Model"),
            firmware=text_of(res, "{*}FirmwareVersion"),
            serial_number=text_of(res, "{*}SerialNumber"),
            hardware_id=text_of(res, "{*}HardwareId"),
        )

    async def get_network_interfaces(self) -> list[NetworkInterface]:
        body = await self._call(self.target.device_url, "GetNetworkInterfaces", "<tds:GetNetworkInterfaces/>")
        result = []
        for nic in body.iterfind(".//{*}NetworkInterfaces"):
            config = nic.find("{*}IPv4/{*}Config")
            from_dhcp = bool_text(text_of(config, "{*}DHCP")) or False
            source = config.find("{*}FromDHCP") if from_dhcp and config is not None else None
            if source is None or not text_of(source, "{*}Address"):
                source = config.find("{*}Manual") if config is not None else None
            if (source is None or not text_of(source, "{*}Address")) and config is not None:
                source = config.find("{*}FromDHCP")
            prefix = text_of(source, "{*}PrefixLength")
            result.append(
                NetworkInterface(
                    token=nic.get("token", ""),
                    enabled=bool_text(text_of(nic, "{*}Enabled")) is not False,
                    mac_address=text_of(nic, "{*}Info/{*}HwAddress"),
                    ipv4_address=text_of(source, "{*}Address"),
                    from_dhcp=from_dhcp,
                    prefix_length=int(prefix) if prefix and prefix.isdigit() else None,
                )
            )
        return result

    async def set_network_interface(
        self, token: str, dhcp: bool, address: str | None, prefix_length: int | None
    ) -> bool:
        """``SetNetworkInterfaces`` cho IPv4; trả ``RebootNeeded`` của thiết bị."""
        manual = (
            f"<tt:Manual><tt:Address>{esc(address)}</tt:Address><tt:PrefixLength>{prefix_length}</tt:PrefixLength>"
            "</tt:Manual>"
            if not dhcp and address and prefix_length
            else ""
        )
        body = await self._call(
            self.target.device_url,
            "SetNetworkInterfaces",
            f"<tds:SetNetworkInterfaces><tds:InterfaceToken>{esc(token)}</tds:InterfaceToken>"
            "<tds:NetworkInterface><tt:Enabled>true</tt:Enabled>"
            f"<tt:IPv4><tt:Enabled>true</tt:Enabled>{manual}<tt:DHCP>{str(dhcp).lower()}</tt:DHCP></tt:IPv4>"
            "</tds:NetworkInterface></tds:SetNetworkInterfaces>",
        )
        self._invalidate()
        return bool_text(text_of(body, ".//{*}RebootNeeded")) or False

    async def get_default_gateway(self) -> str | None:
        body = await self._call(self.target.device_url, "GetNetworkDefaultGateway", "<tds:GetNetworkDefaultGateway/>")
        return text_of(body, ".//{*}NetworkGateway/{*}IPv4Address")

    async def set_default_gateway(self, gateway: str) -> None:
        await self._call(
            self.target.device_url,
            "SetNetworkDefaultGateway",
            f"<tds:SetNetworkDefaultGateway><tds:IPv4Address>{esc(gateway)}</tds:IPv4Address>"
            "</tds:SetNetworkDefaultGateway>",
        )

    async def get_dns(self) -> list[str]:
        body = await self._call(self.target.device_url, "GetDNS", "<tds:GetDNS/>")
        info = body.find(".//{*}DNSInformation")
        from_dhcp = bool_text(text_of(info, "{*}FromDHCP")) or False
        servers: list[str] = []
        if info is not None:
            for item in info.iterfind("{*}DNSFromDHCP" if from_dhcp else "{*}DNSManual"):
                value = text_of(item, "{*}IPv4Address") or text_of(item, "{*}IPv6Address")
                if value and value not in servers:
                    servers.append(value)
        return servers

    async def set_dns(self, servers: list[str]) -> None:
        items = "".join(
            f"<tds:DNSManual><tt:Type>IPv4</tt:Type><tt:IPv4Address>{esc(s)}</tt:IPv4Address></tds:DNSManual>"
            for s in servers
        )
        await self._call(
            self.target.device_url, "SetDNS", f"<tds:SetDNS><tds:FromDHCP>false</tds:FromDHCP>{items}</tds:SetDNS>"
        )

    async def get_hostname(self) -> str | None:
        body = await self._call(self.target.device_url, "GetHostname", "<tds:GetHostname/>")
        return text_of(body, ".//{*}HostnameInformation/{*}Name")

    async def get_ntp(self) -> tuple[bool, list[str]]:
        body = await self._call(self.target.device_url, "GetNTP", "<tds:GetNTP/>")
        info = body.find(".//{*}NTPInformation")
        from_dhcp = bool_text(text_of(info, "{*}FromDHCP")) or False
        servers: list[str] = []
        if info is not None:
            for tag in ("NTPFromDHCP", "NTPManual") if from_dhcp else ("NTPManual", "NTPFromDHCP"):
                for item in info.iterfind("{*}" + tag):
                    value = (
                        text_of(item, "{*}IPv4Address")
                        or text_of(item, "{*}IPv6Address")
                        or text_of(item, "{*}DNSname")
                    )
                    if value and value not in servers:
                        servers.append(value)
        return from_dhcp, servers

    async def set_ntp(self, servers: list[str]) -> None:
        await self.set_ntp_servers(servers)
        # Chuyển nguồn giờ sang NTP, giữ nguyên múi giờ và DST đang dùng
        clock = await self.get_system_date_and_time()
        try:
            await self.set_system_date_and_time("NTP", clock.daylight_savings, clock.timezone)
        except DeviceError as e:
            if e.code != ErrorCode.NOT_SUPPORTED:
                raise

    async def set_system_date_and_time(
        self, date_time_type: str, daylight_savings: bool, timezone: str | None, utc: datetime | None = None
    ) -> None:
        """``SetSystemDateAndTime``; ``date_time_type`` là ``NTP`` hoặc ``Manual`` (khi đó cần ``utc``)."""
        tz = f"<tds:TimeZone><tt:TZ>{esc(timezone)}</tt:TZ></tds:TimeZone>" if timezone else ""
        moment = ""
        if utc is not None:
            u = utc.astimezone(UTC)
            moment = (
                f"<tds:UTCDateTime><tt:Time><tt:Hour>{u.hour}</tt:Hour><tt:Minute>{u.minute}</tt:Minute>"
                f"<tt:Second>{u.second}</tt:Second></tt:Time><tt:Date><tt:Year>{u.year}</tt:Year>"
                f"<tt:Month>{u.month}</tt:Month><tt:Day>{u.day}</tt:Day></tt:Date></tds:UTCDateTime>"
            )
        await self._call(
            self.target.device_url,
            "SetSystemDateAndTime",
            f"<tds:SetSystemDateAndTime><tds:DateTimeType>{esc(date_time_type)}</tds:DateTimeType>"
            f"<tds:DaylightSavings>{str(daylight_savings).lower()}</tds:DaylightSavings>{tz}{moment}"
            "</tds:SetSystemDateAndTime>",
        )
        # Đồng hồ thiết bị đổi thì độ lệch đã nhớ (dùng cho PasswordDigest) không còn đúng
        self._invalidate()

    async def set_ntp_servers(self, servers: list[str]) -> None:
        items = []
        for server in servers:
            try:
                ip = ipaddress.ip_address(server)
                kind = "IPv4" if ip.version == 4 else "IPv6"
                items.append(
                    f"<tds:NTPManual><tt:Type>{kind}</tt:Type><tt:{kind}Address>{esc(server)}</tt:{kind}Address>"
                    "</tds:NTPManual>"
                )
            except ValueError:
                items.append(
                    f"<tds:NTPManual><tt:Type>DNS</tt:Type><tt:DNSname>{esc(server)}</tt:DNSname></tds:NTPManual>"
                )
        await self._call(
            self.target.device_url,
            "SetNTP",
            f"<tds:SetNTP><tds:FromDHCP>false</tds:FromDHCP>{''.join(items)}</tds:SetNTP>",
        )

    async def system_reboot(self) -> str:
        body = await self._call(self.target.device_url, "SystemReboot", "<tds:SystemReboot/>")
        self._invalidate()
        return text_of(body, ".//{*}Message", "") or ""

    async def get_users(self) -> list[tuple[str, str]]:
        body = await self._call(self.target.device_url, "GetUsers", "<tds:GetUsers/>")
        return [
            (text_of(user, "{*}Username", "") or "", text_of(user, "{*}UserLevel", "User") or "User")
            for user in body.iterfind(".//{*}User")
        ]

    async def set_user_password(self, username: str, new_password: str) -> None:
        level = next((lvl for name, lvl in await self.get_users() if name == username), None)
        if level is None:
            raise DeviceError(ErrorCode.DEVICE_ERROR, f"Thiết bị không có tài khoản '{username}'")
        await self._call(
            self.target.device_url,
            "SetUser",
            f"<tds:SetUser><tds:User><tt:Username>{esc(username)}</tt:Username>"
            f"<tt:Password>{esc(new_password)}</tt:Password><tt:UserLevel>{esc(level)}</tt:UserLevel>"
            "</tds:User></tds:SetUser>",
        )

    # ---- Media (ver10) --------------------------------------------------------------------------------

    async def get_profiles(self) -> list[ProfileData]:
        body = await self._call(self.service_url("media"), "GetProfiles", "<trt:GetProfiles/>")
        profiles = []
        for prof in body.iterfind(".//{*}Profiles"):
            vec = prof.find("{*}VideoEncoderConfiguration")
            profiles.append(
                ProfileData(
                    token=prof.get("token", ""),
                    name=text_of(prof, "{*}Name", "") or "",
                    video_source_token=text_of(prof, "{*}VideoSourceConfiguration/{*}SourceToken"),
                    encoder=parse_encoder(vec) if vec is not None else None,
                    has_ptz=prof.find("{*}PTZConfiguration") is not None,
                    has_audio_in=prof.find("{*}AudioSourceConfiguration") is not None,
                    has_audio_out=prof.find(".//{*}AudioOutputConfiguration") is not None,
                    video_source_config_token=_token_of(prof.find("{*}VideoSourceConfiguration")),
                    analytics_token=_token_of(prof.find("{*}VideoAnalyticsConfiguration")),
                    video_source_config_name=text_of(prof, "{*}VideoSourceConfiguration/{*}Name"),
                )
            )
        return profiles

    async def get_stream_uri(self, profile_token: str) -> str | None:
        body = await self._call(
            self.service_url("media"),
            "GetStreamUri",
            "<trt:GetStreamUri><trt:StreamSetup><tt:Stream>RTP-Unicast</tt:Stream>"
            "<tt:Transport><tt:Protocol>RTSP</tt:Protocol></tt:Transport></trt:StreamSetup>"
            f"<trt:ProfileToken>{esc(profile_token)}</trt:ProfileToken></trt:GetStreamUri>",
        )
        return text_of(body, ".//{*}MediaUri/{*}Uri")

    async def get_snapshot_uri(self, profile_token: str) -> str | None:
        body = await self._call(
            self.service_url("media"),
            "GetSnapshotUri",
            f"<trt:GetSnapshotUri><trt:ProfileToken>{esc(profile_token)}</trt:ProfileToken></trt:GetSnapshotUri>",
        )
        return text_of(body, ".//{*}MediaUri/{*}Uri")

    async def get_video_sources(self) -> list[str]:
        return [vs.token for vs in await self.get_video_source_details()]

    async def get_video_source_details(self) -> list[VideoSourceData]:
        body = await self._call(self.service_url("media"), "GetVideoSources", "<trt:GetVideoSources/>")
        return [
            VideoSourceData(
                token=vs.get("token", ""),
                framerate=float_of(vs, "{*}Framerate"),
                resolution=(
                    (int_of(vs, "{*}Resolution/{*}Width"), int_of(vs, "{*}Resolution/{*}Height"))
                    if vs.find("{*}Resolution") is not None
                    else None
                ),
            )
            for vs in body.iterfind(".//{*}VideoSources")
            if vs.get("token")
        ]

    async def get_video_encoder_configurations(self) -> list[EncoderData]:
        body = await self._call(
            self.service_url("media"), "GetVideoEncoderConfigurations", "<trt:GetVideoEncoderConfigurations/>"
        )
        return [parse_encoder(cfg) for cfg in body.iterfind(".//{*}Configurations")]

    async def _get_video_encoder_configuration_raw(self, token: str) -> Element:
        body = await self._call(
            self.service_url("media"),
            "GetVideoEncoderConfiguration",
            "<trt:GetVideoEncoderConfiguration>"
            f"<trt:ConfigurationToken>{esc(token)}</trt:ConfigurationToken></trt:GetVideoEncoderConfiguration>",
        )
        config = body.find(".//{*}Configuration")
        if config is None:
            raise DeviceError(ErrorCode.DEVICE_ERROR, f"Thiết bị không trả cấu hình bộ mã hóa '{token}'")
        return config

    async def get_video_encoder_options(self, token: str) -> EncoderOptions:
        body = await self._call(
            self.service_url("media"),
            "GetVideoEncoderConfigurationOptions",
            "<trt:GetVideoEncoderConfigurationOptions>"
            f"<trt:ConfigurationToken>{esc(token)}</trt:ConfigurationToken>"
            "</trt:GetVideoEncoderConfigurationOptions>",
        )
        options = body.find(".//{*}Options")
        if options is None:
            return EncoderOptions()
        return parse_encoder_options(options)

    async def set_video_encoder_configuration(
        self,
        token: str,
        *,
        codec: str | None = None,
        width: int | None = None,
        height: int | None = None,
        fps: int | None = None,
        bitrate_kbps: int | None = None,
        gov_length: int | None = None,
    ) -> EncoderData:
        """Đọc cấu hình hiện tại, sửa các trường được yêu cầu rồi ghi lại (giữ nguyên phần còn lại)."""
        options = await self.get_video_encoder_options(token)
        config = await self._get_video_encoder_configuration_raw(token)
        if codec is not None and options.codecs and codec not in options.codecs:
            raise DeviceError(
                ErrorCode.NOT_SUPPORTED,
                f"Thiết bị không hỗ trợ chuẩn nén {codec} (hỗ trợ: {', '.join(options.codecs)})",
            )
        if (width is None) != (height is None):
            raise DeviceError(ErrorCode.DEVICE_ERROR, "Cần đặt đồng thời width và height")
        if (
            width is not None
            and options.resolutions
            and not any(r.width == width and r.height == height for r in options.resolutions)
        ):
            raise DeviceError(ErrorCode.NOT_SUPPORTED, f"Thiết bị không hỗ trợ độ phân giải {width}x{height}")
        if codec is not None:
            soap.set_child_text(config, ["Encoding"], codec)
            if codec == "H264":
                soap.ensure_h264(config)
        if width is not None and height is not None:
            soap.set_child_text(config, ["Resolution", "Width"], str(width))
            soap.set_child_text(config, ["Resolution", "Height"], str(height))
        if fps is not None:
            soap.set_child_text(config, ["RateControl", "FrameRateLimit"], str(fps))
        if bitrate_kbps is not None:
            soap.set_child_text(config, ["RateControl", "BitrateLimit"], str(bitrate_kbps))
        if gov_length is not None:
            current = normalize_codec(text_of(config, "{*}Encoding"))
            group = "MPEG4" if current == "MPEG4" else "H264"
            if config.find("{*}" + group) is None and group == "H264":
                soap.ensure_h264(config)
            soap.set_child_text(config, [group, "GovLength"], str(gov_length))
        await self._call(
            self.service_url("media"),
            "SetVideoEncoderConfiguration",
            "<trt:SetVideoEncoderConfiguration>"
            f"{soap.serialize_as(config, 'trt', 'Configuration')}"
            "<trt:ForcePersistence>true</trt:ForcePersistence></trt:SetVideoEncoderConfiguration>",
        )
        return parse_encoder(config)

    # ---- PTZ (ver20) ----------------------------------------------------------------------------------

    async def ptz_continuous_move(
        self, profile_token: str, pan: float, tilt: float, zoom: float, timeout_ms: int | None = None
    ) -> None:
        velocity = ""
        if pan or tilt:
            velocity += f"<tt:PanTilt x={attr(_num(pan))} y={attr(_num(tilt))}/>"
        if zoom:
            velocity += f"<tt:Zoom x={attr(_num(zoom))}/>"
        if not velocity:
            await self.ptz_stop(profile_token)
            return
        timeout = f"<tptz:Timeout>{_duration(timeout_ms / 1000)}</tptz:Timeout>" if timeout_ms else ""
        await self._call(
            self.service_url("ptz"),
            "ContinuousMove",
            f"<tptz:ContinuousMove><tptz:ProfileToken>{esc(profile_token)}</tptz:ProfileToken>"
            f"<tptz:Velocity>{velocity}</tptz:Velocity>{timeout}</tptz:ContinuousMove>",
        )

    async def ptz_stop(self, profile_token: str) -> None:
        await self._call(
            self.service_url("ptz"),
            "Stop",
            f"<tptz:Stop><tptz:ProfileToken>{esc(profile_token)}</tptz:ProfileToken>"
            "<tptz:PanTilt>true</tptz:PanTilt><tptz:Zoom>true</tptz:Zoom></tptz:Stop>",
        )

    async def ptz_get_presets(self, profile_token: str) -> list[Preset]:
        body = await self._call(
            self.service_url("ptz"),
            "GetPresets",
            f"<tptz:GetPresets><tptz:ProfileToken>{esc(profile_token)}</tptz:ProfileToken></tptz:GetPresets>",
        )
        return [
            Preset(token=p.get("token", ""), name=text_of(p, "{*}Name", "") or "")
            for p in body.iterfind(".//{*}Preset")
            if p.get("token")
        ]

    async def ptz_goto_preset(self, profile_token: str, preset_token: str) -> None:
        await self._call(
            self.service_url("ptz"),
            "GotoPreset",
            f"<tptz:GotoPreset><tptz:ProfileToken>{esc(profile_token)}</tptz:ProfileToken>"
            f"<tptz:PresetToken>{esc(preset_token)}</tptz:PresetToken></tptz:GotoPreset>",
        )

    async def ptz_set_preset(self, profile_token: str, name: str, preset_token: str | None = None) -> str:
        token = f"<tptz:PresetToken>{esc(preset_token)}</tptz:PresetToken>" if preset_token else ""
        body = await self._call(
            self.service_url("ptz"),
            "SetPreset",
            f"<tptz:SetPreset><tptz:ProfileToken>{esc(profile_token)}</tptz:ProfileToken>"
            f"<tptz:PresetName>{esc(name)}</tptz:PresetName>{token}</tptz:SetPreset>",
        )
        result = text_of(body, ".//{*}PresetToken")
        if not result:
            raise DeviceError(ErrorCode.DEVICE_ERROR, "Thiết bị không trả mã preset")
        return result

    async def ptz_remove_preset(self, profile_token: str, preset_token: str) -> None:
        await self._call(
            self.service_url("ptz"),
            "RemovePreset",
            f"<tptz:RemovePreset><tptz:ProfileToken>{esc(profile_token)}</tptz:ProfileToken>"
            f"<tptz:PresetToken>{esc(preset_token)}</tptz:PresetToken></tptz:RemovePreset>",
        )

    # ---- Imaging (ver20) ------------------------------------------------------------------------------

    async def default_video_source(self) -> str:
        sources = await self.get_video_sources()
        if not sources:
            raise DeviceError(ErrorCode.NOT_SUPPORTED, "Thiết bị không có nguồn video")
        return sources[0]

    async def get_imaging_settings(self, video_source_token: str) -> ImagingSettings:
        body = await self._call(
            self.service_url("imaging"),
            "GetImagingSettings",
            "<timg:GetImagingSettings>"
            f"<timg:VideoSourceToken>{esc(video_source_token)}</timg:VideoSourceToken></timg:GetImagingSettings>",
        )
        settings = body.find(".//{*}ImagingSettings")
        ir = text_of(settings, "{*}IrCutFilter")
        wdr = text_of(settings, "{*}WideDynamicRange/{*}Mode")
        return ImagingSettings(
            video_source_token=video_source_token,
            brightness=float_of(settings, "{*}Brightness"),
            contrast=float_of(settings, "{*}Contrast"),
            ir_mode=ONVIF_TO_IR.get((ir or "").upper()),
            wdr=None if wdr is None else wdr.upper() == "ON",
        )

    async def get_imaging_options(self, video_source_token: str) -> ImagingOptions:
        body = await self._call(
            self.service_url("imaging"),
            "GetOptions",
            f"<timg:GetOptions><timg:VideoSourceToken>{esc(video_source_token)}</timg:VideoSourceToken></timg:GetOptions>",
        )
        options = body.find(".//{*}ImagingOptions")
        ir_modes: list[IrMode] = []
        if options is not None:
            for mode in options.iterfind("{*}IrCutFilterModes"):
                mapped = ONVIF_TO_IR.get((mode.text or "").strip().upper())
                if mapped and mapped not in ir_modes:
                    ir_modes.append(mapped)
        wdr_modes = (
            [(m.text or "").strip().upper() for m in options.iterfind("{*}WideDynamicRange/{*}Mode")]
            if options is not None
            else []
        )
        return ImagingOptions(
            brightness=_range(options, "{*}Brightness"),
            contrast=_range(options, "{*}Contrast"),
            ir_modes=ir_modes,
            wdr="ON" in wdr_modes,
        )

    async def set_imaging_settings(self, video_source_token: str, update: ImagingUpdate) -> None:
        # Thứ tự phần tử theo lược đồ tt:ImagingSettings20
        parts = ""
        if update.brightness is not None:
            parts += f"<tt:Brightness>{_num(update.brightness)}</tt:Brightness>"
        if update.contrast is not None:
            parts += f"<tt:Contrast>{_num(update.contrast)}</tt:Contrast>"
        if update.ir_mode is not None:
            parts += f"<tt:IrCutFilter>{IR_TO_ONVIF[update.ir_mode]}</tt:IrCutFilter>"
        if update.wdr is not None:
            parts += f"<tt:WideDynamicRange><tt:Mode>{'ON' if update.wdr else 'OFF'}</tt:Mode></tt:WideDynamicRange>"
        if not parts:
            return
        await self._call(
            self.service_url("imaging"),
            "SetImagingSettings",
            f"<timg:SetImagingSettings><timg:VideoSourceToken>{esc(video_source_token)}</timg:VideoSourceToken>"
            f"<timg:ImagingSettings>{parts}</timg:ImagingSettings>"
            "<timg:ForcePersistence>true</timg:ForcePersistence></timg:SetImagingSettings>",
        )

    # ---- Analytics (ver20) ----------------------------------------------------------------------------

    async def get_supported_rules(self, config_token: str) -> dict[str, int | None]:
        """Loại quy tắc thiết bị hỗ trợ (tên cục bộ, ví dụ ``CellMotionDetector``) → số quy tắc tối đa nếu có."""
        body = await self._call(
            self.service_url("analytics"),
            "GetSupportedRules",
            f"<tan:GetSupportedRules><tan:ConfigurationToken>{esc(config_token)}</tan:ConfigurationToken>"
            "</tan:GetSupportedRules>",
        )
        result: dict[str, int | None] = {}
        for desc in body.iter():
            if local_name(desc.tag) != "RuleDescription" or not desc.get("Name"):
                continue
            raw = desc.get("maxInstances") or desc.get("MaxInstances")
            try:
                limit = int(raw) if raw else None
            except ValueError:
                limit = None
            result[soap.strip_prefix(desc.get("Name", ""))] = limit
        return result

    async def get_rules(self, config_token: str) -> list[Element]:
        body = await self._call(
            self.service_url("analytics"),
            "GetRules",
            f"<tan:GetRules><tan:ConfigurationToken>{esc(config_token)}</tan:ConfigurationToken></tan:GetRules>",
        )
        return list(body.iterfind(".//{*}GetRulesResponse/{*}Rule"))

    async def create_rules(self, config_token: str, rules: list[str]) -> None:
        """``rules`` là các phần tử ``tan:Rule`` đã dựng sẵn."""
        if not rules:
            return
        await self._call(
            self.service_url("analytics"),
            "CreateRules",
            f"<tan:CreateRules><tan:ConfigurationToken>{esc(config_token)}</tan:ConfigurationToken>"
            f"{''.join(rules)}</tan:CreateRules>",
        )

    async def modify_rules(self, config_token: str, rules: list[str]) -> None:
        if not rules:
            return
        await self._call(
            self.service_url("analytics"),
            "ModifyRules",
            f"<tan:ModifyRules><tan:ConfigurationToken>{esc(config_token)}</tan:ConfigurationToken>"
            f"{''.join(rules)}</tan:ModifyRules>",
        )

    async def delete_rules(self, config_token: str, names: list[str]) -> None:
        if not names:
            return
        items = "".join(f"<tan:RuleName>{esc(n)}</tan:RuleName>" for n in names)
        await self._call(
            self.service_url("analytics"),
            "DeleteRules",
            f"<tan:DeleteRules><tan:ConfigurationToken>{esc(config_token)}</tan:ConfigurationToken>"
            f"{items}</tan:DeleteRules>",
        )

    async def get_analytics_modules(self, config_token: str) -> list[Element]:
        body = await self._call(
            self.service_url("analytics"),
            "GetAnalyticsModules",
            f"<tan:GetAnalyticsModules><tan:ConfigurationToken>{esc(config_token)}</tan:ConfigurationToken>"
            "</tan:GetAnalyticsModules>",
        )
        return list(body.iterfind(".//{*}GetAnalyticsModulesResponse/{*}AnalyticsModule"))

    async def modify_analytics_modules(self, config_token: str, modules: list[str]) -> None:
        if not modules:
            return
        await self._call(
            self.service_url("analytics"),
            "ModifyAnalyticsModules",
            f"<tan:ModifyAnalyticsModules><tan:ConfigurationToken>{esc(config_token)}</tan:ConfigurationToken>"
            f"{''.join(modules)}</tan:ModifyAnalyticsModules>",
        )

    # ---- Media2: vùng che riêng tư --------------------------------------------------------------------

    async def get_mask_options(self, config_token: str) -> MaskOptionsData:
        body = await self._call(
            self.service_url("media2"),
            "GetMaskOptions",
            f"<tr2:GetMaskOptions><tr2:ConfigurationToken>{esc(config_token)}</tr2:ConfigurationToken>"
            "</tr2:GetMaskOptions>",
        )
        options = body.find(".//{*}GetMaskOptionsResponse/{*}Options")
        if options is None:
            raise DeviceError(ErrorCode.NOT_SUPPORTED, "Thiết bị không trả tùy chọn vùng che")

        def limit(name: str) -> int | None:
            try:
                return int(options.get(name, ""))
            except ValueError:
                return None

        return MaskOptionsData(
            max_masks=limit("MaxMasks"),
            max_points=limit("MaxPoints"),
            rectangle_only=bool_text(options.get("RectangleOnly")) is True,
            types=[t.text.strip() for t in options.iterfind("{*}Types") if t.text and t.text.strip()],
        )

    async def get_masks(self, config_token: str) -> list[MaskData]:
        body = await self._call(
            self.service_url("media2"),
            "GetMasks",
            f"<tr2:GetMasks><tr2:ConfigurationToken>{esc(config_token)}</tr2:ConfigurationToken></tr2:GetMasks>",
        )
        result = []
        for mask in body.iterfind(".//{*}GetMasksResponse/{*}Masks"):
            owner = text_of(mask, "{*}ConfigurationToken")
            if owner and owner != config_token:
                continue
            result.append(
                MaskData(
                    token=mask.get("token", ""),
                    points=polygon_points(mask.find("{*}Polygon")),
                    enabled=bool_text(text_of(mask, "{*}Enabled")) is not False,
                )
            )
        return result

    async def create_mask(
        self, config_token: str, points: list[tuple[float, float]], enabled: bool, mask_type: str = "Color"
    ) -> str:
        polygon = "".join(f"<tt:Point x={attr(_num(x))} y={attr(_num(y))}/>" for x, y in points)
        # Màu đen theo YCbCr; chỉ gửi khi kiểu che là tô màu
        color = (
            '<tr2:Color X="16" Y="128" Z="128" Colorspace="http://www.onvif.org/ver10/colorspace/YCbCr"/>'
            if mask_type == "Color"
            else ""
        )
        body = await self._call(
            self.service_url("media2"),
            "CreateMask",
            f"<tr2:CreateMask><tr2:Mask><tr2:ConfigurationToken>{esc(config_token)}</tr2:ConfigurationToken>"
            f"<tr2:Polygon>{polygon}</tr2:Polygon><tr2:Type>{esc(mask_type)}</tr2:Type>{color}"
            f"<tr2:Enabled>{str(enabled).lower()}</tr2:Enabled></tr2:Mask></tr2:CreateMask>",
        )
        return text_of(body, ".//{*}Token", "") or ""

    async def delete_mask(self, token: str) -> None:
        await self._call(
            self.service_url("media2"),
            "DeleteMask",
            f"<tr2:DeleteMask><tr2:Token>{esc(token)}</tr2:Token></tr2:DeleteMask>",
        )

    # ---- Events (PullPoint) ---------------------------------------------------------------------------

    async def supports_motion_events(self) -> bool:
        body = await self._call(self.service_url("events"), "GetEventProperties", "<tev:GetEventProperties/>")
        topics = body.find(".//{*}TopicSet")
        if topics is None:
            return False
        return any(any(h in local_name(n.tag).lower() for h in _MOTION_TOPIC_HINTS) for n in topics.iter())

    async def create_pull_point(self, ttl_seconds: int) -> PullPoint:
        body = await self._call(
            self.service_url("events"),
            "CreatePullPointSubscription",
            "<tev:CreatePullPointSubscription>"
            f"<tev:InitialTerminationTime>{_duration(ttl_seconds)}</tev:InitialTerminationTime>"
            "</tev:CreatePullPointSubscription>",
            action=f"{_EVENTS_ACTION}/EventPortType/CreatePullPointSubscriptionRequest",
        )
        ref = body.find(".//{*}SubscriptionReference")
        address = text_of(ref, "{*}Address")
        if not address:
            raise DeviceError(ErrorCode.DEVICE_ERROR, "Thiết bị không trả địa chỉ PullPoint")
        params = ref.find("{*}ReferenceParameters") if ref is not None else None
        extra = "".join(soap.serialize(child) for child in params) if params is not None else ""
        return PullPoint(address=self.rewrite_url(address), reference_parameters=extra)

    def _subscription_header(self, pull_point: PullPoint, action: str) -> str:
        return (
            f"<wsa:Action>{esc(action)}</wsa:Action><wsa:To>{esc(pull_point.address)}</wsa:To>"
            f"{pull_point.reference_parameters}"
        )

    async def pull_messages(self, pull_point: PullPoint, timeout_seconds: int, limit: int = 32) -> list[RawEvent]:
        action = f"{_EVENTS_ACTION}/PullPointSubscription/PullMessagesRequest"
        body = await self._call(
            pull_point.address,
            "PullMessages",
            f"<tev:PullMessages><tev:Timeout>{_duration(timeout_seconds)}</tev:Timeout>"
            f"<tev:MessageLimit>{limit}</tev:MessageLimit></tev:PullMessages>",
            action=action,
            extra_header=self._subscription_header(pull_point, action),
            timeout=timeout_seconds + self._timeout,
        )
        events = []
        for nm in body.iterfind(".//{*}NotificationMessage"):
            topic = text_of(nm, "{*}Topic", "") or ""
            msg = nm.find("{*}Message/{*}Message")
            if msg is None:
                continue
            events.append(
                RawEvent(
                    topic=topic,
                    utc_time=msg.get("UtcTime"),
                    property_operation=msg.get("PropertyOperation"),
                    source=_simple_items(msg, "{*}Source/{*}SimpleItem"),
                    data=_simple_items(msg, "{*}Data/{*}SimpleItem"),
                )
            )
        return events

    async def renew(self, pull_point: PullPoint, ttl_seconds: int) -> None:
        action = f"{_WSN_ACTION}/RenewRequest"
        await self._call(
            pull_point.address,
            "Renew",
            f"<wsnt:Renew><wsnt:TerminationTime>{_duration(ttl_seconds)}</wsnt:TerminationTime></wsnt:Renew>",
            action=action,
            extra_header=self._subscription_header(pull_point, action),
        )

    async def unsubscribe(self, pull_point: PullPoint) -> None:
        action = f"{_WSN_ACTION}/UnsubscribeRequest"
        await self._call(
            pull_point.address,
            "Unsubscribe",
            "<wsnt:Unsubscribe/>",
            action=action,
            extra_header=self._subscription_header(pull_point, action),
        )

    # ---- Search / Recording / Replay (Profile G) ------------------------------------------------------

    async def find_recordings(self) -> list[RecordingData]:
        """Mọi bản ghi trên thiết bị (FindRecordings + GetRecordingSearchResults)."""
        body = await self._call(
            self.service_url("search"),
            "FindRecordings",
            "<tse:FindRecordings><tse:Scope/><tse:KeepAliveTime>PT30S</tse:KeepAliveTime></tse:FindRecordings>",
        )
        items = await self._search_results(body, "GetRecordingSearchResults", "RecordingInformation")
        return [r for r in (parse_recording(i) for i in items) if r.token]

    async def find_track_state_events(
        self, start: datetime, end: datetime, recording_tokens: list[str]
    ) -> list[TrackStateEvent]:
        """Sự kiện có/mất dữ liệu track trong [start, end] (kèm trạng thái tại ``start``), theo thời gian tăng dần."""
        included = "".join(f"<tt:IncludedRecordings>{esc(t)}</tt:IncludedRecordings>" for t in recording_tokens)
        body = await self._call(
            self.service_url("search"),
            "FindEvents",
            f"<tse:FindEvents><tse:StartPoint>{format_xs_datetime(start)}</tse:StartPoint>"
            f"<tse:EndPoint>{format_xs_datetime(end)}</tse:EndPoint><tse:Scope>{included}</tse:Scope>"
            f"<tse:SearchFilter><tt:TopicExpression Dialect={attr(_CONCRETE_SET)} xmlns:tns1={attr(_TOPICS_NS)}>"
            f"{_TRACK_STATE_TOPIC}</tt:TopicExpression></tse:SearchFilter>"
            "<tse:IncludeStartState>true</tse:IncludeStartState><tse:KeepAliveTime>PT30S</tse:KeepAliveTime>"
            "</tse:FindEvents>",
        )
        items = await self._search_results(body, "GetEventSearchResults", "Result")
        events = [e for e in (parse_track_state_event(i) for i in items) if e is not None]
        return sorted(events, key=lambda e: (e.time, not e.start_state))

    async def _search_results(self, find_body: Element, op: str, item: str) -> list[Element]:
        """Đọc hết các trang kết quả của một phiên tìm kiếm; phiên chưa xong thì gọi EndSearch."""
        token = text_of(find_body, ".//{*}SearchToken")
        if not token:
            raise DeviceError(ErrorCode.DEVICE_ERROR, "Thiết bị không trả SearchToken")
        results: list[Element] = []
        completed = False
        try:
            for _ in range(_SEARCH_MAX_PAGES):
                body = await self._call(
                    self.service_url("search"),
                    op,
                    f"<tse:{op}><tse:SearchToken>{esc(token)}</tse:SearchToken><tse:MinResults>1</tse:MinResults>"
                    f"<tse:MaxResults>{_SEARCH_PAGE}</tse:MaxResults><tse:WaitTime>PT5S</tse:WaitTime></tse:{op}>",
                )
                result_list = body.find(".//{*}ResultList")
                if result_list is None:
                    break
                results += result_list.findall("{*}" + item)
                if (text_of(result_list, "{*}SearchState") or "Completed") == "Completed":
                    completed = True
                    break
            else:
                log.warning("Tìm kiếm %s trên %s chưa xong sau %d trang", op, self.target.label, _SEARCH_MAX_PAGES)
        finally:
            if not completed:
                await self._end_search(token)
        return results

    async def _end_search(self, token: str) -> None:
        try:
            await self._call(
                self.service_url("search"),
                "EndSearch",
                f"<tse:EndSearch><tse:SearchToken>{esc(token)}</tse:SearchToken></tse:EndSearch>",
            )
        except DeviceError as e:
            log.info("Không kết thúc được phiên tìm kiếm trên %s: %s", self.target.label, e.detail)

    async def get_recording_job_sources(self) -> list[tuple[str, str]]:
        """(RecordingToken, token profile media) của các job ghi (GetRecordingJobs, Recording service)."""
        body = await self._call(self.service_url("recording"), "GetRecordingJobs", "<trc:GetRecordingJobs/>")
        result = []
        for job in body.iterfind(".//{*}JobItem"):
            config = job.find("{*}JobConfiguration")
            recording = text_of(config, "{*}RecordingToken")
            if config is None or not recording:
                continue
            for source in config.iterfind("{*}Source/{*}SourceToken"):
                token = text_of(source, "{*}Token")
                kind = source.get("Type", "")
                if token and (not kind or kind.endswith("/Profile")):
                    result.append((recording, token))
        return result

    async def get_replay_uri(self, recording_token: str) -> str | None:
        body = await self._call(
            self.service_url("replay"),
            "GetReplayUri",
            "<trp:GetReplayUri><trp:StreamSetup><tt:Stream>RTP-Unicast</tt:Stream>"
            "<tt:Transport><tt:Protocol>RTSP</tt:Protocol></tt:Transport></trp:StreamSetup>"
            f"<trp:RecordingToken>{esc(recording_token)}</trp:RecordingToken></trp:GetReplayUri>",
        )
        return text_of(body, ".//{*}Uri")

    # ---- Ảnh chụp -------------------------------------------------------------------------------------

    async def fetch_snapshot(self, uri: str) -> tuple[bytes, str]:
        """Tải ảnh JPEG từ URI thiết bị trả về, thử Digest rồi Basic theo thử thách WWW-Authenticate."""
        uri = self.rewrite_url(uri)
        if urlsplit(uri).scheme not in ("http", "https"):
            raise DeviceError(ErrorCode.NOT_SUPPORTED, "URI ảnh chụp không phải HTTP")
        result = "OK"
        started = time.perf_counter()
        try:
            res = await self._get_limited(uri, None)
            if res[0] == 401:
                challenge = res[2].lower()
                auth: httpx.Auth = (
                    httpx.DigestAuth(self.target.username, self.target.password)
                    if challenge.startswith("digest")
                    else httpx.BasicAuth(self.target.username, self.target.password)
                )
                res = await self._get_limited(uri, auth)
            status, content, _, content_type = res
            if status == 401:
                raise DeviceError(ErrorCode.AUTH_FAILED, "Thiết bị từ chối tài khoản khi lấy ảnh chụp")
            if status >= 400:
                raise DeviceError(ErrorCode.DEVICE_ERROR, f"Thiết bị trả HTTP {status} khi lấy ảnh chụp")
            if not content_type.startswith("image/") and not content.startswith(b"\xff\xd8"):
                raise DeviceError(ErrorCode.DEVICE_ERROR, "Thiết bị không trả dữ liệu ảnh")
            return content, content_type if content_type.startswith("image/") else "image/jpeg"
        except DeviceError as e:
            result = e.code
            raise
        except httpx.TransportError as e:
            result = ErrorCode.UNREACHABLE
            raise DeviceError(ErrorCode.UNREACHABLE, f"Không tải được ảnh chụp từ {self.target.label}") from e
        finally:
            DEVICE_CALLS.labels(op="Snapshot", result=str(result)).inc()
            DEVICE_CALL_SECONDS.labels(op="Snapshot").observe(time.perf_counter() - started)

    async def _get_limited(self, uri: str, auth: httpx.Auth | None) -> tuple[int, bytes, str, str]:
        async with self._http.stream("GET", uri, auth=auth, timeout=self._timeout) as res:
            chunks = bytearray()
            async for chunk in res.aiter_bytes():
                chunks += chunk
                if len(chunks) > _SNAPSHOT_MAX_BYTES:
                    raise DeviceError(ErrorCode.DEVICE_ERROR, "Ảnh chụp vượt quá 10 MB")
            content_type = res.headers.get("content-type", "").split(";")[0].strip().lower()
            return res.status_code, bytes(chunks), res.headers.get("www-authenticate", ""), content_type


def _token_of(el: Element | None) -> str | None:
    return (el.get("token") or None) if el is not None else None


def polygon_points(polygon: Element | None) -> list[tuple[float, float]]:
    """Đỉnh ``tt:Point x y`` trong một đa giác (bỏ qua điểm thiếu tọa độ)."""
    if polygon is None:
        return []
    points = []
    for p in polygon.iter():
        if local_name(p.tag) != "Point":
            continue
        try:
            points.append((float(p.get("x", "")), float(p.get("y", ""))))
        except ValueError:
            continue
    return points


def _simple_items(msg: Element, path: str) -> dict[str, str]:
    return {item.get("Name", ""): item.get("Value", "") for item in msg.iterfind(path) if item.get("Name")}
