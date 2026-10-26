"""Schema REST nội bộ Core ↔ Gateway. JSON camelCase, khớp record trong ``DeviceControlPort`` phía Core."""

from __future__ import annotations

import ipaddress
import re
from datetime import UTC, datetime, timedelta
from typing import Annotated, Literal

from pydantic import AfterValidator, BaseModel, ConfigDict, Field, SecretStr, model_validator
from pydantic.alias_generators import to_camel

from app.transport.tunnel_router import normalize_proxy

IrMode = Literal["AUTO", "DAY", "NIGHT"]
Codec = Literal["H264", "H265", "JPEG", "MPEG4"]
# Proxy SOCKS5 của tunnel site (TS-06.3), ví dụ ``socks5://tunnel:21001``; ``None`` là gọi thẳng
ProxyUrl = Annotated[str | None, Field(max_length=300), AfterValidator(normalize_proxy)]


def _as_utc(moment: datetime) -> datetime:
    """Thời điểm không kèm múi giờ được coi là UTC."""
    return moment.replace(tzinfo=UTC) if moment.tzinfo is None else moment.astimezone(UTC)


UtcDateTime = Annotated[datetime, AfterValidator(_as_utc)]
# Cửa sổ tìm bản ghi trên thiết bị tối đa 31 ngày (đầu ghi trả nhiều sự kiện khi cửa sổ dài)
MAX_SEARCH_WINDOW = timedelta(days=31)


class CamelModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class DeviceAddress(CamelModel):
    """Địa chỉ và tài khoản thiết bị. Mật khẩu là ``SecretStr`` nên không lộ ra khi log/repr."""

    host: str = Field(min_length=1, max_length=255)
    port: int = Field(default=80, ge=1, le=65535)
    username: str = ""
    password: SecretStr = SecretStr("")
    # Camera ở site xa: mọi lời gọi HTTP tới thiết bị đi qua proxy này (địa chỉ LAN của site vẫn dùng nguyên)
    proxy: ProxyUrl = None


class ErrorBody(CamelModel):
    code: str
    detail: str | None = None


# ---- Dò tìm -------------------------------------------------------------------------------------------


class DiscoverRequest(CamelModel):
    targets: list[str] = Field(default_factory=list, max_length=256)
    timeout_seconds: int = Field(default=5, ge=1, le=60)


class DiscoveredDevice(CamelModel):
    host: str
    port: int
    manufacturer: str | None = None
    model: str | None = None
    mac_address: str | None = None
    hardware: str | None = None
    scopes: list[str] = Field(default_factory=list)


# ---- Thông tin thiết bị --------------------------------------------------------------------------------


class Capabilities(CamelModel):
    ptz: bool = False
    audio_in: bool = False
    audio_out: bool = False
    imaging: bool = False
    events: bool = False
    edge_recording: bool = False
    motion_detection: bool = False
    privacy_mask: bool = False


class StreamProfile(CamelModel):
    token: str
    name: str
    type: Literal["MAIN", "SUB", "OTHER"] = "OTHER"
    codec: str | None = None
    width: int = 0
    height: int = 0
    fps: int = 0
    bitrate_kbps: int = 0
    rtsp_url: str | None = None
    b_frames: bool = False


class DeviceInfo(CamelModel):
    manufacturer: str | None = None
    model: str | None = None
    firmware: str | None = None
    serial_number: str | None = None
    mac_address: str | None = None
    capabilities: Capabilities
    profiles: list[StreamProfile]


# ---- Kênh đầu ghi NVR/DVR (US-03.1) --------------------------------------------------------------------


class ChannelResolution(CamelModel):
    width: int
    height: int


class RecorderChannel(CamelModel):
    channel: int  # đánh số từ 1 theo thứ tự nguồn video thiết bị trả
    name: str
    video_source_token: str
    profile_token: str | None = None
    sub_profile_token: str | None = None
    has_signal: bool
    resolution: ChannelResolution | None = None
    rtsp_url: str | None = None
    sub_rtsp_url: str | None = None


class RecorderChannels(CamelModel):
    manufacturer: str | None = None
    model: str | None = None
    channel_count: int
    channels: list[RecorderChannel]


# ---- Phát lại bản ghi trên thiết bị (US-22.1, ONVIF Profile G) -----------------------------------------


class RecordingSearchRequest(CamelModel):
    device: DeviceAddress
    from_: UtcDateTime = Field(alias="from")
    to: UtcDateTime
    # Kênh đầu ghi: chỉ lấy bản ghi của nguồn video này; ``None`` là mọi bản ghi
    video_source_token: str | None = Field(default=None, max_length=128)

    @model_validator(mode="after")
    def _check_window(self) -> RecordingSearchRequest:
        if self.to <= self.from_:
            raise ValueError("'to' phải sau 'from'")
        if self.to - self.from_ > MAX_SEARCH_WINDOW:
            raise ValueError("Cửa sổ tìm kiếm tối đa 31 ngày")
        return self


class RecordingRange(CamelModel):
    start: str
    end: str


class EdgeRecording(CamelModel):
    recording_token: str
    video_source_token: str | None = None
    track_token: str | None = None
    ranges: list[RecordingRange] = Field(default_factory=list)


class RecordingSearchResult(CamelModel):
    supported: bool
    reason: str | None = None
    recordings: list[EdgeRecording] = Field(default_factory=list)


class ReplayUriRequest(CamelModel):
    device: DeviceAddress
    recording_token: str = Field(min_length=1, max_length=128)
    start: UtcDateTime


class ReplayUri(CamelModel):
    uri: str


# ---- PTZ -----------------------------------------------------------------------------------------------


class PtzRequest(CamelModel):
    device: DeviceAddress
    profile_token: str = Field(min_length=1)


class PtzMoveRequest(PtzRequest):
    pan: float = Field(default=0.0, ge=-1.0, le=1.0)
    tilt: float = Field(default=0.0, ge=-1.0, le=1.0)
    zoom: float = Field(default=0.0, ge=-1.0, le=1.0)
    timeout_ms: int | None = Field(default=None, ge=100, le=120_000)


class Preset(CamelModel):
    token: str
    name: str


class PresetGotoRequest(PtzRequest):
    preset_token: str = Field(min_length=1)


class PresetSetRequest(PtzRequest):
    name: str = Field(min_length=1, max_length=64)
    preset_token: str | None = None


class PresetToken(CamelModel):
    token: str


# ---- Hình ảnh ------------------------------------------------------------------------------------------


class Range(CamelModel):
    min: float
    max: float


class ImagingOptions(CamelModel):
    brightness: Range | None = None
    contrast: Range | None = None
    ir_modes: list[IrMode] = Field(default_factory=list)
    wdr: bool = False


class ImagingSettings(CamelModel):
    video_source_token: str
    brightness: float | None = None
    contrast: float | None = None
    ir_mode: IrMode | None = None
    wdr: bool | None = None
    options: ImagingOptions = Field(default_factory=ImagingOptions)


class ImagingGetRequest(CamelModel):
    device: DeviceAddress
    video_source_token: str | None = None


class ImagingUpdate(CamelModel):
    brightness: float | None = None
    contrast: float | None = None
    ir_mode: IrMode | None = None
    wdr: bool | None = None


class ImagingSetRequest(ImagingGetRequest):
    settings: ImagingUpdate


# ---- Bộ mã hóa -----------------------------------------------------------------------------------------


class Resolution(CamelModel):
    width: int
    height: int


class EncoderOptions(CamelModel):
    codecs: list[str] = Field(default_factory=list)
    resolutions: list[Resolution] = Field(default_factory=list)
    fps_range: Range | None = None
    bitrate_range: Range | None = None
    gov_length_range: Range | None = None
    quality_range: Range | None = None
    h264_profiles: list[str] = Field(default_factory=list)


class EncoderConfig(CamelModel):
    token: str
    name: str
    codec: str | None = None
    width: int = 0
    height: int = 0
    fps: int = 0
    bitrate_kbps: int = 0
    gov_length: int | None = None
    quality: float | None = None
    h264_profile: str | None = None
    b_frames: bool = False
    options: EncoderOptions | None = None


class DeviceRequest(CamelModel):
    device: DeviceAddress


class EncoderSetRequest(DeviceRequest):
    configuration_token: str = Field(min_length=1)
    codec: Codec | None = None
    width: int | None = Field(default=None, ge=16, le=16384)
    height: int | None = Field(default=None, ge=16, le=16384)
    fps: int | None = Field(default=None, ge=1, le=240)
    bitrate_kbps: int | None = Field(default=None, ge=16, le=200_000)
    gov_length: int | None = Field(default=None, ge=1, le=1000)


# ---- Mạng, thời gian, hệ thống -------------------------------------------------------------------------


TimeMode = Literal["NTP", "MANUAL"]

_HOSTNAME_RE = re.compile(
    r"^(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$"
)
# Múi giờ POSIX 1003.1 như ONVIF yêu cầu, ví dụ ``ICT-7``, ``<+07>-7``, ``CET-1CEST,M3.5.0,M10.5.0/3``
_TZ_NAME = r"(?:[A-Za-z]{3,}|<[A-Za-z0-9+-]{1,10}>)"
_TZ_OFFSET = r"[+-]?\d{1,2}(?::\d{2}){0,2}"
_POSIX_TZ_RE = re.compile(rf"^{_TZ_NAME}{_TZ_OFFSET}(?:{_TZ_NAME}(?:{_TZ_OFFSET})?(?:,[A-Za-z0-9./:+-]+){{0,2}})?$")


def _device_ipv4(value: str) -> str:
    """Địa chỉ IPv4 dùng được cho thiết bị (không phải 0.0.0.0, loopback, multicast, broadcast)."""
    try:
        ip = ipaddress.IPv4Address(value.strip())
    except ValueError as e:
        raise ValueError("Không phải địa chỉ IPv4 hợp lệ") from e
    if ip.is_unspecified or ip.is_loopback or ip.is_multicast or ip == ipaddress.IPv4Address("255.255.255.255"):
        raise ValueError("Địa chỉ IPv4 không dùng được cho thiết bị")
    return str(ip)


def _ntp_server(value: str) -> str:
    server = value.strip()
    try:
        return str(ipaddress.ip_address(server))
    except ValueError:
        pass
    if not _HOSTNAME_RE.match(server):
        raise ValueError("Máy chủ NTP phải là địa chỉ IP hoặc tên miền")
    return server


def _posix_tz(value: str) -> str:
    if not _POSIX_TZ_RE.match(value):
        raise ValueError("Múi giờ phải theo định dạng POSIX, ví dụ ICT-7")
    return value


DeviceIpv4 = Annotated[str, AfterValidator(_device_ipv4)]
NtpServer = Annotated[str, AfterValidator(_ntp_server)]
PosixTz = Annotated[str, Field(min_length=3, max_length=64), AfterValidator(_posix_tz)]


class NetworkInfo(CamelModel):
    ip_address: str | None = None
    prefix_length: int | None = None
    gateway: str | None = None
    dns: list[str] = Field(default_factory=list)
    mac_address: str | None = None
    hostname: str | None = None
    ntp_servers: list[str] = Field(default_factory=list)
    ntp_from_dhcp: bool = False
    from_dhcp: bool = False
    # Nguồn giờ đang dùng; ``None`` khi thiết bị trả giá trị lạ
    time_mode: TimeMode | None = None
    timezone: str | None = None
    device_time: str
    # Đồng hồ thiết bị trừ đồng hồ Gateway (giây, có dấu): dương là thiết bị chạy nhanh
    clock_drift_seconds: float


class NtpSetRequest(DeviceRequest):
    servers: list[NtpServer] = Field(min_length=1, max_length=4)


class NetworkSetRequest(DeviceRequest):
    """Cấu hình IPv4 của card mạng đầu tiên đang bật (US-25.1).

    ``dhcp`` bật thì không nhận ``address``/``prefixLength``/``gateway``; ``dns`` ``null`` là giữ nguyên DNS.
    """

    dhcp: bool
    address: DeviceIpv4 | None = None
    prefix_length: int | None = Field(default=None, ge=1, le=32)
    gateway: DeviceIpv4 | None = None
    dns: list[DeviceIpv4] | None = Field(default=None, min_length=1, max_length=4)

    @model_validator(mode="after")
    def _check_addressing(self) -> NetworkSetRequest:
        if self.dhcp:
            if self.address is not None or self.prefix_length is not None or self.gateway is not None:
                raise ValueError("Đang dùng DHCP thì không đặt address, prefixLength, gateway")
            return self
        if self.address is None or self.prefix_length is None:
            raise ValueError("IP tĩnh cần address và prefixLength")
        ip = ipaddress.IPv4Address(self.address)
        net = ipaddress.IPv4Network(f"{ip}/{self.prefix_length}", strict=False)
        if self.prefix_length <= 30 and ip in (net.network_address, net.broadcast_address):
            raise ValueError("address là địa chỉ mạng hoặc broadcast của dải")
        if self.gateway is not None:
            gateway = ipaddress.IPv4Address(self.gateway)
            if gateway not in net or gateway == ip:
                raise ValueError("gateway phải nằm cùng dải với address và khác address")
        return self


class NetworkSetResult(CamelModel):
    reboot_needed: bool
    # IP mới khi khác IP hiện tại của card mạng (Core cập nhật host nếu đang gọi thẳng theo IP); DHCP là ``None``
    new_host: str | None = None


class TimeSetRequest(DeviceRequest):
    """Nguồn giờ của thiết bị (US-25.1): ``NTP`` (kèm ``ntpServers`` nếu muốn đổi máy chủ) hoặc ``MANUAL`` + ``utc``."""

    mode: TimeMode
    ntp_servers: list[NtpServer] | None = Field(default=None, min_length=1, max_length=4)
    utc: UtcDateTime | None = None
    timezone: PosixTz | None = None

    @model_validator(mode="after")
    def _check_mode(self) -> TimeSetRequest:
        if self.mode == "MANUAL":
            if self.utc is None:
                raise ValueError("Chế độ MANUAL cần utc")
            if self.ntp_servers is not None:
                raise ValueError("Chế độ MANUAL không nhận ntpServers")
            if not 2000 <= self.utc.year <= 2099:
                raise ValueError("utc nằm ngoài khoảng 2000–2099")
        elif self.utc is not None:
            raise ValueError("Chế độ NTP không nhận utc")
        return self


class PasswordRequest(DeviceRequest):
    username: str = Field(min_length=1)
    new_password: SecretStr = Field(min_length=1)


class SnapshotRequest(DeviceRequest):
    profile_token: str | None = None


class Message(CamelModel):
    message: str


# ---- Vùng chuyển động và vùng che riêng tư (US-26) -----------------------------------------------------

ZoneMode = Literal["CELL", "POLYGON"]


class Point(CamelModel):
    """Tọa độ chuẩn hóa 0..1 trên khung hình, gốc ở góc trên bên trái."""

    x: float = Field(ge=0.0, le=1.0)
    y: float = Field(ge=0.0, le=1.0)


Polygon = Annotated[list[Point], Field(min_length=3, max_length=64)]


class Grid(CamelModel):
    columns: int
    rows: int


class MotionZones(CamelModel):
    supported: bool
    mode: ZoneMode | None = None
    grid: Grid | None = None
    # rows × columns, True là ô được bật phát hiện chuyển động
    cells: list[list[bool]] | None = None
    polygons: list[list[Point]] = Field(default_factory=list)
    sensitivity: int | None = None
    max_polygons: int | None = None


class PrivacyMask(CamelModel):
    token: str | None = None
    points: list[Point]
    enabled: bool = True


class PrivacyMasks(CamelModel):
    supported: bool
    max_masks: int | None = None
    max_points: int | None = None
    items: list[PrivacyMask] = Field(default_factory=list)


class ZonesConfig(CamelModel):
    motion: MotionZones
    masks: PrivacyMasks


class MotionZonesSetRequest(DeviceRequest):
    mode: ZoneMode
    cells: list[list[bool]] | None = Field(default=None, max_length=256)
    polygons: list[Polygon] = Field(default_factory=list, max_length=32)
    sensitivity: int | None = Field(default=None, ge=0, le=100)


class PrivacyMaskInput(CamelModel):
    points: Polygon
    enabled: bool = True


class PrivacyMasksSetRequest(DeviceRequest):
    items: list[PrivacyMaskInput] = Field(default_factory=list, max_length=64)


# ---- Sự kiện -------------------------------------------------------------------------------------------


class SubscriptionRequest(DeviceRequest):
    camera_id: str = Field(min_length=1, max_length=64)


class SubscriptionStatus(CamelModel):
    camera_id: str
    host: str
    port: int
    status: Literal["STARTING", "ACTIVE", "RECONNECTING", "OFFLINE", "UNSUPPORTED", "STOPPED"]
    since: str
    last_error: str | None = None
    last_event_at: str | None = None
    motion: bool | None = None


# ---- Theo dõi trạng thái (US-04.1) ---------------------------------------------------------------------


class MonitorTarget(CamelModel):
    camera_id: str = Field(min_length=1, max_length=64)
    host: str = Field(min_length=1, max_length=255)
    port: int = Field(default=80, ge=1, le=65535)
    protocol: Literal["ONVIF", "RTSP"] = "ONVIF"
    rtsp_host: str | None = Field(default=None, max_length=255)
    rtsp_port: int | None = Field(default=None, ge=1, le=65535)
    # Kiểm tra ONVIF/RTSP qua tunnel site, giống ``DeviceAddress.proxy``
    proxy: ProxyUrl = None


class MonitorTargetsRequest(CamelModel):
    targets: list[MonitorTarget] = Field(max_length=5000)
    interval_seconds: int = Field(default=30, ge=1, le=3600)


class MonitorTargetStatus(MonitorTarget):
    status: Literal["ONLINE", "OFFLINE", "UNKNOWN"]
    last_change_at: str | None = None
    last_check_at: str | None = None
    last_error: str | None = None
