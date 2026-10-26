"""Cấu hình bộ giả lập đọc từ biến môi trường ``SIM_*``."""

from __future__ import annotations

import os
import socket
import uuid
from dataclasses import dataclass, field


def _bool(value: str | None, default: bool) -> bool:
    if value is None or value == "":
        return default
    return value.strip().lower() in ("1", "true", "yes", "on")


def _int_list(value: str | None) -> list[int] | None:
    """``"1,2,3"`` → ``[1, 2, 3]``; ``None`` khi không đặt biến, ``[]`` khi đặt ``none``/rỗng."""
    if value is None:
        return None
    value = value.strip()
    if value.lower() in ("", "none"):
        return []
    return [int(v) for v in value.split(",") if v.strip()]


def _str_list(value: str | None) -> list[str]:
    return [v.strip() for v in (value or "").split(",") if v.strip()]


# Nhãn mặc định của kênh đầu ghi giả lập; kênh vượt danh sách chỉ có số kênh
DEFAULT_CHANNEL_LABELS = (
    "Cổng",
    "Sảnh",
    "Bãi xe",
    "Kho",
    "Hành lang tầng 1",
    "Hành lang tầng 2",
    "Thang máy",
    "Cầu thang",
    "Phòng máy",
    "Lễ tân",
    "Sân sau",
    "Hàng rào",
    "Nhà ăn",
    "Văn phòng",
    "Xưởng",
    "Cổng phụ",
)


def detect_local_ip() -> str:
    """IP của card mạng ra ngoài (không gửi gói nào); không xác định được thì dùng 127.0.0.1."""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("192.0.2.1", 9))
            return str(s.getsockname()[0])
    except OSError:
        return "127.0.0.1"


@dataclass
class SimConfig:
    name: str = "CamHub Sim"
    manufacturer: str = "CamHub"
    model: str = "SIM-1000"
    firmware: str = "1.0.0"
    serial: str = "SIM0000001"
    mac: str = "02:00:00:00:00:01"
    user: str = "admin"
    password: str = "admin123"
    http_port: int = 8080
    advertise_host: str = "127.0.0.1"
    advertise_port: int = 0
    rtsp_main: str = ""
    rtsp_sub: str = ""
    ptz: bool = True
    bframes: bool = False
    # Cổng UDP WS-Discovery; 0 là tắt
    discovery_port: int = 3702
    discovery_bind: str = "0.0.0.0"  # noqa: S104 - nhận Probe multicast từ mọi card mạng
    # Lệch đồng hồ giả lập (giây), để kiểm tra Gateway bù giờ khi tính PasswordDigest
    clock_offset_seconds: float = 0.0
    # Chuyển sang nguồn giờ NTP thì coi như đồng bộ thành công: độ lệch đồng hồ về 0 (US-25.1)
    ntp_sync: bool = False
    # "cell" → tns1:RuleEngine/CellMotionDetector/Motion (IsMotion); "alarm" → tns1:VideoSource/MotionAlarm (State)
    motion_topic: str = "cell"
    # Dịch vụ Analytics (vùng chuyển động) và Media2 (vùng che riêng tư)
    zones: bool = True
    # Chế độ đầu ghi NVR/DVR (US-03.1): số kênh > 0 thì giả lập đầu ghi thay vì camera đơn
    channels: int = 0
    # Kênh có tín hiệu (đánh số từ 1); None là mọi kênh
    signal_channels: list[int] | None = None
    # URI RTSP luồng chính/phụ của các kênh có tín hiệu, theo thứ tự ``signal_channels``;
    # thiếu thì dùng dạng Hikvision rtsp://<host>:554/Streaming/Channels/<N>01 (/<N>02)
    channel_rtsp_main: list[str] = field(default_factory=list)
    channel_rtsp_sub: list[str] = field(default_factory=list)
    # Nhãn kênh theo thứ tự kênh; tên kênh là "Kênh 01 – <nhãn>"
    channel_labels: list[str] = field(default_factory=lambda: list(DEFAULT_CHANNEL_LABELS))
    # Ghi hình trên thiết bị (Profile G, US-22.1): camera đơn bật bằng ``SIM_EDGE_RECORDING``, đầu ghi luôn có
    edge_recording: bool = False
    # Độ dài dữ liệu ghi giả lập tính lùi từ bây giờ (giờ)
    edge_hours: float = 24.0
    endpoint_uuid: str = field(default_factory=lambda: str(uuid.uuid4()))

    def __post_init__(self) -> None:
        if not self.advertise_port:
            self.advertise_port = self.http_port
        host = self.advertise_host
        self.rtsp_main = self.rtsp_main or f"rtsp://{host}:554/Streaming/Channels/101"
        self.rtsp_sub = self.rtsp_sub or f"rtsp://{host}:554/Streaming/Channels/102"

    @property
    def base_url(self) -> str:
        return f"http://{self.advertise_host}:{self.advertise_port}"

    @property
    def is_recorder(self) -> bool:
        return self.channels > 0

    @property
    def has_edge_recording(self) -> bool:
        return self.edge_recording or self.is_recorder

    def channel_has_signal(self, channel: int) -> bool:
        return self.signal_channels is None or channel in self.signal_channels

    def channel_name(self, channel: int) -> str:
        label = self.channel_labels[channel - 1] if channel <= len(self.channel_labels) else ""
        return f"Kênh {channel:02d} – {label}" if label else f"Kênh {channel:02d}"

    def channel_rtsp(self, channel: int) -> tuple[str, str]:
        """URI luồng chính và phụ của một kênh đầu ghi."""
        main = f"rtsp://{self.advertise_host}:554/Streaming/Channels/{channel}01"
        sub = f"rtsp://{self.advertise_host}:554/Streaming/Channels/{channel}02"
        order = self.signal_channels if self.signal_channels is not None else list(range(1, self.channels + 1))
        if channel in order:
            index = order.index(channel)
            if index < len(self.channel_rtsp_main):
                main = self.channel_rtsp_main[index]
                sub = self.channel_rtsp_sub[index] if index < len(self.channel_rtsp_sub) else main
        return main, sub

    @classmethod
    def from_env(cls) -> SimConfig:
        env = os.environ
        http_port = int(env.get("SIM_HTTP_PORT", "8080"))
        return cls(
            name=env.get("SIM_NAME", cls.name),
            manufacturer=env.get("SIM_MANUFACTURER", cls.manufacturer),
            model=env.get("SIM_MODEL", cls.model),
            firmware=env.get("SIM_FIRMWARE", cls.firmware),
            serial=env.get("SIM_SERIAL", cls.serial),
            mac=env.get("SIM_MAC", cls.mac),
            user=env.get("SIM_USER", cls.user),
            password=env.get("SIM_PASSWORD", cls.password),
            http_port=http_port,
            advertise_host=env.get("SIM_ADVERTISE_HOST") or detect_local_ip(),
            advertise_port=int(env.get("SIM_ADVERTISE_PORT", "0") or 0),
            rtsp_main=env.get("SIM_RTSP_MAIN", ""),
            rtsp_sub=env.get("SIM_RTSP_SUB", ""),
            ptz=_bool(env.get("SIM_PTZ"), True),
            bframes=_bool(env.get("SIM_BFRAMES"), False),
            discovery_port=int(env.get("SIM_DISCOVERY_PORT", "3702")),
            clock_offset_seconds=float(env.get("SIM_CLOCK_OFFSET_SECONDS", "0") or 0),
            ntp_sync=_bool(env.get("SIM_NTP_SYNC"), False),
            motion_topic=env.get("SIM_MOTION_TOPIC", "cell"),
            zones=_bool(env.get("SIM_ZONES"), True),
            channels=int(env.get("SIM_CHANNELS", "0") or 0),
            signal_channels=_int_list(env.get("SIM_SIGNAL_CHANNELS")),
            channel_rtsp_main=_str_list(env.get("SIM_CHANNEL_RTSP_MAIN")),
            channel_rtsp_sub=_str_list(env.get("SIM_CHANNEL_RTSP_SUB")),
            channel_labels=_str_list(env.get("SIM_CHANNEL_NAMES")) or list(DEFAULT_CHANNEL_LABELS),
            edge_recording=_bool(env.get("SIM_EDGE_RECORDING"), False),
            edge_hours=float(env.get("SIM_EDGE_HOURS", "24") or 24),
        )
