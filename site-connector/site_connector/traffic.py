"""Đo lưu lượng đường hầm (best effort, chỉ Linux).

chisel client không xuất bộ đếm byte, nên đo bộ đếm của card mạng đi ra máy chủ trong /proc/net/dev.
Card được chọn theo bảng định tuyến /proc/net/route (khớp tiền tố dài nhất với IP của máy chủ), hoặc đặt cứng
bằng CAMHUB_WAN_INTERFACE. Trong container chỉ có lưu lượng của bộ kết nối, nên số đo ≈ lưu lượng đường hầm
(cộng phần nhỏ của heartbeat/API). txBytes là chiều đi lên máy chủ (video từ camera), rxBytes là chiều về.
Không đọc được (Windows, không có /proc) thì mọi giá trị bằng 0.
"""

from __future__ import annotations

import logging
import socket
import struct
import time
from dataclasses import dataclass
from pathlib import Path

log = logging.getLogger(__name__)

PROC_NET_DEV = Path("/proc/net/dev")
PROC_NET_ROUTE = Path("/proc/net/route")


@dataclass(frozen=True)
class TrafficSample:
    rx_bytes: int
    tx_bytes: int
    rx_kbps: float
    tx_kbps: float


def parse_net_dev(text: str) -> dict[str, tuple[int, int]]:
    """Trả {card: (rx_bytes, tx_bytes)} từ nội dung /proc/net/dev."""
    result: dict[str, tuple[int, int]] = {}
    for line in text.splitlines()[2:]:
        name, sep, rest = line.partition(":")
        if not sep:
            continue
        fields = rest.split()
        if len(fields) >= 9:
            result[name.strip()] = (int(fields[0]), int(fields[8]))
    return result


def _hex_ip(value: str) -> int:
    # /proc/net/route ghi địa chỉ dạng hex theo thứ tự byte của máy (little-endian trên x86/ARM)
    return int.from_bytes(struct.pack("<I", int(value, 16)), "big")


def route_interface(text: str, ip: str) -> str | None:
    """Card mạng mà gói tới ``ip`` đi qua, theo tiền tố dài nhất trong /proc/net/route."""
    target = int.from_bytes(socket.inet_aton(ip), "big")
    best: tuple[int, str] | None = None
    for line in text.splitlines()[1:]:
        cols = line.split()
        if len(cols) < 8:
            continue
        try:
            dest, mask = _hex_ip(cols[1]), _hex_ip(cols[7])
        except ValueError:
            continue
        if target & mask == dest & mask:
            prefix = bin(mask).count("1")
            if best is None or prefix > best[0]:
                best = (prefix, cols[0])
    return best[1] if best else None


class TrafficMeter:
    def __init__(self, server_host: str, interface: str = "") -> None:
        self._server_host = server_host
        self._interface = interface or None
        self._last: tuple[float, int, int] | None = None
        self._warned = False

    def _resolve_interface(self) -> str | None:
        if self._interface:
            return self._interface
        try:
            ip = socket.getaddrinfo(self._server_host, 443, socket.AF_INET, socket.SOCK_STREAM)[0][4][0]
            name = route_interface(PROC_NET_ROUTE.read_text(), str(ip))
        except (OSError, IndexError, ValueError):
            return None
        if name:
            log.info("Đo lưu lượng trên card %s (đường tới %s)", name, ip)
            self._interface = name
        return name

    def sample(self) -> TrafficSample:
        """Bộ đếm hiện tại và tốc độ trung bình kể từ lần đo trước (kbit/s)."""
        name = self._resolve_interface()
        counters = None
        if name:
            try:
                counters = parse_net_dev(PROC_NET_DEV.read_text()).get(name)
            except OSError:
                counters = None
        if counters is None:
            if not self._warned:
                log.info("Không đo được lưu lượng (không có /proc/net/dev hoặc không xác định được card mạng)")
                self._warned = True
            return TrafficSample(0, 0, 0.0, 0.0)
        now = time.monotonic()
        rx, tx = counters
        rx_kbps = tx_kbps = 0.0
        if self._last is not None:
            elapsed = now - self._last[0]
            if elapsed > 0:
                rx_kbps = max(0, rx - self._last[1]) * 8 / 1000 / elapsed
                tx_kbps = max(0, tx - self._last[2]) * 8 / 1000 / elapsed
        self._last = (now, rx, tx)
        return TrafficSample(rx, tx, rx_kbps, tx_kbps)
