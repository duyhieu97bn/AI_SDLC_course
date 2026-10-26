"""Trạng thái trong bộ nhớ của camera giả lập."""

from __future__ import annotations

import asyncio
import time
import uuid
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta

from onvif_sim.cells import encode_active_cells
from onvif_sim.config import SimConfig

# Giới hạn vùng của camera giả lập (US-26)
GRID_COLUMNS = 22
GRID_ROWS = 18
MAX_CELL_RULES = 1
MAX_REGION_RULES = 4
MAX_MASKS = 4
MAX_MASK_POINTS = 8
CELL_RULE_NAME = "MyMotionDetectorRule"


@dataclass
class Encoder:
    token: str
    name: str
    encoding: str
    width: int
    height: int
    fps: int
    bitrate: int
    gov: int
    h264_profile: str
    quality: float
    resolutions: list[tuple[int, int]]
    max_fps: int
    bitrate_range: tuple[int, int]
    b_frames: int = 0


@dataclass
class Profile:
    token: str
    name: str
    encoder: Encoder
    rtsp: str
    audio: bool = False
    # VideoSourceConfiguration của profile; đầu ghi đặt tên kênh ở config_name
    source_token: str = "vs0"
    config_token: str = "vsc0"
    config_name: str = "Video"


@dataclass
class VideoSource:
    """Nguồn video (camera đơn có một, đầu ghi có một nguồn mỗi kênh).

    Kênh mất tín hiệu được báo như Hikvision/Dahua: ``Framerate`` 0 và ``Resolution`` 0×0.
    """

    token: str
    channel: int = 0
    signal: bool = True
    width: int = 1920
    height: int = 1080
    framerate: int = 25


@dataclass
class Imaging:
    brightness: float = 50.0
    contrast: float = 50.0
    ir_cut: str = "AUTO"
    wdr: str = "OFF"


@dataclass
class Rule:
    """Quy tắc Analytics; ``type`` là tên cục bộ (``CellMotionDetector``/``MotionRegionDetector``)."""

    name: str
    type: str
    items: dict[str, str] = field(default_factory=dict)
    # Đa giác của MotionRegionDetector theo hệ ONVIF −1..1
    polygon: list[tuple[float, float]] = field(default_factory=list)


@dataclass
class Mask:
    token: str
    points: list[tuple[float, float]]
    enabled: bool = True
    type: str = "Color"


@dataclass
class Subscription:
    id: str
    expires: float
    queue: asyncio.Queue[str] = field(default_factory=asyncio.Queue)


@dataclass
class SimState:
    config: SimConfig
    profiles: list[Profile] = field(default_factory=list)
    sources: list[VideoSource] = field(default_factory=list)
    imaging: Imaging = field(default_factory=Imaging)
    users: dict[str, tuple[str, str]] = field(default_factory=dict)
    ntp_from_dhcp: bool = False
    ntp_servers: list[str] = field(default_factory=lambda: ["pool.ntp.org"])
    date_time_type: str = "Manual"
    daylight_savings: bool = False
    timezone: str = "ICT-7"
    hostname: str = "camhub-sim"
    # Lệch đồng hồ hiện tại (giây); khởi tạo từ cấu hình, đổi qua SetSystemDateAndTime hoặc POST /sim/clock
    clock_offset: float = 0.0
    # Cấu hình mạng chỉ lưu trong bộ nhớ, không đổi IP thật của container; None là địa chỉ quảng bá
    ipv4_address: str | None = None
    prefix_length: int = 24
    dhcp: bool = False
    default_gateway: str | None = None
    dns_servers: list[str] = field(default_factory=list)
    presets: dict[str, str] = field(default_factory=dict)
    next_preset: int = 1
    ptz_velocity: tuple[float, float, float] = (0.0, 0.0, 0.0)
    ptz_moves: int = 0
    reboots: int = 0
    motion: bool = False
    subscriptions: dict[str, Subscription] = field(default_factory=dict)
    cell_sensitivity: int = 50
    rules: dict[str, Rule] = field(default_factory=dict)
    masks: dict[str, Mask] = field(default_factory=dict)
    next_mask: int = 1

    @classmethod
    def create(cls, config: SimConfig) -> SimState:
        state = cls(config=config, clock_offset=config.clock_offset_seconds)
        if config.is_recorder:
            for n in range(1, config.channels + 1):
                rtsp_main, rtsp_sub = config.channel_rtsp(n)
                source = f"VideoSource_{n}"
                vsc = f"VideoSourceConfig_{n}"
                name = config.channel_name(n)
                state.profiles += [
                    Profile(
                        f"Profile_{n}01",
                        "mainStream",
                        _main_encoder(f"VideoEncoder_{n}01", "MainStream", False),
                        rtsp_main,
                        source_token=source,
                        config_token=vsc,
                        config_name=name,
                    ),
                    Profile(
                        f"Profile_{n}02",
                        "subStream",
                        _sub_encoder(f"VideoEncoder_{n}02", "SubStream"),
                        rtsp_sub,
                        source_token=source,
                        config_token=vsc,
                        config_name=name,
                    ),
                ]
                state.sources.append(VideoSource(source, n, config.channel_has_signal(n)))
        else:
            state.profiles = [
                Profile(
                    "profile_main",
                    "MainStream",
                    _main_encoder("vec_main", "MainStream", config.bframes),
                    config.rtsp_main,
                    audio=True,
                ),
                Profile("profile_sub", "SubStream", _sub_encoder("vec_sub", "SubStream"), config.rtsp_sub),
            ]
            state.sources = [VideoSource("vs0")]
        state.users = {config.user: (config.password, "Administrator")}
        if config.ptz:
            state.presets = {"preset_1": "Cổng chính"}
            state.next_preset = 2
        if config.zones:
            # Như camera thật: quy tắc lưới mặc định bật toàn bộ ô
            state.rules[CELL_RULE_NAME] = Rule(
                CELL_RULE_NAME,
                "CellMotionDetector",
                {
                    "MinCount": "5",
                    "AlarmOnDelay": "1000",
                    "AlarmOffDelay": "1000",
                    "ActiveCells": encode_active_cells([True] * (GRID_COLUMNS * GRID_ROWS)),
                },
            )
        return state

    def now(self) -> datetime:
        return datetime.now(UTC) + timedelta(seconds=self.clock_offset)

    def passwords(self) -> dict[str, str]:
        return {name: pwd for name, (pwd, _) in self.users.items()}

    def profile(self, token: str | None) -> Profile | None:
        return next((p for p in self.profiles if p.token == token), None)

    def source(self, token: str | None) -> VideoSource | None:
        return next((s for s in self.sources if s.token == token), None)

    def encoder(self, token: str | None) -> Encoder | None:
        return next((p.encoder for p in self.profiles if p.encoder.token == token), None)

    def new_subscription(self, ttl: float) -> Subscription:
        self.expire_subscriptions()
        sub = Subscription(id=uuid.uuid4().hex, expires=time.monotonic() + ttl)
        self.subscriptions[sub.id] = sub
        return sub

    def expire_subscriptions(self) -> None:
        now = time.monotonic()
        for key in [k for k, s in self.subscriptions.items() if s.expires < now]:
            del self.subscriptions[key]


def _main_encoder(token: str, name: str, bframes: bool) -> Encoder:
    return Encoder(
        token=token,
        name=name,
        encoding="H264",
        width=1920,
        height=1080,
        fps=25,
        bitrate=4096,
        gov=50,
        h264_profile="High" if bframes else "Main",
        quality=4.0,
        resolutions=[(1920, 1080), (1280, 720)],
        max_fps=25,
        bitrate_range=(256, 8192),
        b_frames=2 if bframes else 0,
    )


def _sub_encoder(token: str, name: str) -> Encoder:
    return Encoder(
        token=token,
        name=name,
        encoding="H264",
        width=640,
        height=360,
        fps=15,
        bitrate=512,
        gov=30,
        h264_profile="Main",
        quality=3.0,
        resolutions=[(640, 360), (704, 576), (352, 288)],
        max_fps=25,
        bitrate_range=(64, 2048),
    )
