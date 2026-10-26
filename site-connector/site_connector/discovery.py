"""Việc DISCOVERY: dò camera ONVIF trong LAN của địa điểm bằng WS-Discovery.

Gửi Probe multicast 239.255.255.250:3702 trên MỌI card mạng IPv4 (bộ kết nối thường có nhiều card: LAN camera
và đường ra Internet; multicast mặc định chỉ đi theo card của tuyến mặc định), cộng Probe unicast tới danh sách
CAMHUB_DISCOVERY_TARGETS nếu mạng chặn multicast. Host/cổng lấy từ XAddrs, hãng/model lấy từ scope
onvif://www.onvif.org/{name,hardware,mfr,mac}/...

Cùng cách làm với device-gateway/app/discovery/ws_discovery.py nhưng viết lại độc lập (gói này chạy tại địa điểm).
"""

from __future__ import annotations

import asyncio
import ipaddress
import logging
import socket
import struct
import sys
import uuid
from dataclasses import asdict, dataclass, field
from typing import Any
from urllib.parse import unquote, urlsplit

from defusedxml import ElementTree

log = logging.getLogger(__name__)

MULTICAST_GROUP = "239.255.255.250"
DEFAULT_PORT = 3702
MAX_TARGETS = 1024
_SCOPE_PREFIX = "onvif://www.onvif.org/"
_PROBE_TYPES = (
    ("dn", "http://www.onvif.org/ver10/network/wsdl", "NetworkVideoTransmitter"),
    ("tds", "http://www.onvif.org/ver10/device/wsdl", "Device"),
)
_SIOCGIFADDR = 0x8915


@dataclass
class Device:
    host: str
    port: int
    manufacturer: str | None = None
    model: str | None = None
    macAddress: str | None = None  # noqa: N815 - tên trường theo hợp đồng JSON với Core
    hardware: str | None = None
    scopes: list[str] = field(default_factory=list)

    def to_json(self) -> dict[str, Any]:
        return asdict(self)


def expand_targets(targets: list[str], limit: int = MAX_TARGETS) -> list[str]:
    """Đổi danh sách IP/CIDR thành danh sách IPv4 (tối đa ``limit``). Sai định dạng thì ``ValueError``."""
    result: list[str] = []
    for raw in targets:
        network = ipaddress.ip_network(raw.strip(), strict=False)
        if network.version != 4:
            raise ValueError(f"Chỉ hỗ trợ IPv4: '{raw}'")
        hosts = [network.network_address] if network.num_addresses == 1 else list(network.hosts())
        for ip in hosts:
            if str(ip) not in result:
                if len(result) >= limit:
                    raise ValueError(f"Quá nhiều địa chỉ cần dò (tối đa {limit})")
                result.append(str(ip))
    return result


def build_probe(message_id: str, prefix: str, namespace: str, type_name: str) -> bytes:
    return (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" '
        'xmlns:a="http://schemas.xmlsoap.org/ws/2004/08/addressing" '
        'xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery" '
        f'xmlns:{prefix}="{namespace}">'
        "<s:Header>"
        '<a:Action s:mustUnderstand="1">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</a:Action>'
        f"<a:MessageID>urn:uuid:{message_id}</a:MessageID>"
        "<a:ReplyTo><a:Address>http://schemas.xmlsoap.org/ws/2004/08/addressing/role/anonymous</a:Address></a:ReplyTo>"
        '<a:To s:mustUnderstand="1">urn:schemas-xmlsoap-org:ws:2005:04:discovery</a:To>'
        "</s:Header>"
        f"<s:Body><d:Probe><d:Types>{prefix}:{type_name}</d:Types></d:Probe></s:Body>"
        "</s:Envelope>"
    ).encode()


def normalize_mac(raw: str) -> str:
    hexdigits = "".join(c for c in raw if c.isalnum()).upper()
    if len(hexdigits) == 12 and all(c in "0123456789ABCDEF" for c in hexdigits):
        return ":".join(hexdigits[i : i + 2] for i in range(0, 12, 2))
    return raw


def parse_scopes(scopes: list[str]) -> dict[str, str]:
    result: dict[str, str] = {}
    for scope in scopes:
        if not scope.lower().startswith(_SCOPE_PREFIX):
            continue
        key, _, value = scope[len(_SCOPE_PREFIX) :].partition("/")
        key = key.lower()
        if key == "mfr":
            key = "manufacturer"
        if key in ("name", "hardware", "manufacturer", "mac") and value and key not in result:
            result[key] = unquote(value).strip()
    return result


def _pick_xaddr(xaddrs: list[str], sender: str | None) -> tuple[str, int] | None:
    parsed = []
    for xaddr in xaddrs:
        parts = urlsplit(xaddr)
        if parts.scheme not in ("http", "https") or not parts.hostname:
            continue
        parsed.append((parts.hostname, parts.port or (443 if parts.scheme == "https" else 80)))
    if not parsed:
        return None
    for host, port in parsed:
        if host == sender:
            return host, port
    ipv4 = [p for p in parsed if ":" not in p[0]]
    return (ipv4 or parsed)[0]


def _text(element: Any, path: str) -> str:
    found = element.find(path)
    return (found.text or "") if found is not None else ""


def parse_probe_matches(data: bytes, sender: str | None = None) -> list[Device]:
    """Đọc gói ProbeMatches; gói hỏng hoặc không phải ProbeMatches trả danh sách rỗng."""
    try:
        root = ElementTree.fromstring(data)
    except Exception:  # noqa: BLE001 - mọi lỗi phân tích (kể cả XML độc hại bị defusedxml chặn) đều bỏ qua gói
        return []
    devices = []
    for match in root.iterfind(".//{*}ProbeMatch"):
        address = _pick_xaddr(_text(match, "{*}XAddrs").split(), sender)
        if address is None:
            continue
        scopes = _text(match, "{*}Scopes").split()
        info = parse_scopes(scopes)
        devices.append(
            Device(
                host=address[0],
                port=address[1],
                manufacturer=info.get("manufacturer") or info.get("name"),
                model=info.get("hardware") or info.get("name"),
                macAddress=normalize_mac(info["mac"]) if "mac" in info else None,
                hardware=info.get("hardware"),
                scopes=scopes,
            )
        )
    return devices


def local_ipv4_addresses() -> list[str]:
    """IPv4 của mọi card (trừ loopback) qua ioctl SIOCGIFADDR – chỉ Linux; nơi khác trả danh sách rỗng."""
    if sys.platform != "linux":
        return []
    import fcntl

    addresses: list[str] = []
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        for _, name in socket.if_nameindex():
            if name == "lo":
                continue
            try:
                packed = fcntl.ioctl(s.fileno(), _SIOCGIFADDR, struct.pack("256s", name.encode()[:15]))
            except OSError:
                continue
            ip = socket.inet_ntoa(packed[20:24])
            if not ip.startswith("127.") and ip not in addresses:
                addresses.append(ip)
    return addresses


class _Collector(asyncio.DatagramProtocol):
    def __init__(self) -> None:
        self.found: dict[str, Device] = {}

    def datagram_received(self, data: bytes, addr: tuple[str | Any, ...]) -> None:
        sender = addr[0] if addr and isinstance(addr[0], str) else None
        for device in parse_probe_matches(data, sender):
            existing = self.found.get(device.host)
            if existing is None:
                self.found[device.host] = device
                continue
            existing.scopes = list(dict.fromkeys(existing.scopes + device.scopes))
            existing.manufacturer = existing.manufacturer or device.manufacturer
            existing.model = existing.model or device.model
            existing.macAddress = existing.macAddress or device.macAddress
            existing.hardware = existing.hardware or device.hardware

    def error_received(self, exc: Exception) -> None:
        log.debug("Lỗi UDP khi dò WS-Discovery: %s", exc)


async def discover(
    timeout_seconds: float,
    targets: list[str] | None = None,
    *,
    port: int = DEFAULT_PORT,
    interfaces: list[str] | None = None,
) -> list[Device]:
    """Dò thiết bị ONVIF trong ``timeout_seconds`` giây, khử trùng theo host."""
    unicast = expand_targets(targets or [])
    if_addrs = local_ipv4_addresses() if interfaces is None else interfaces
    loop = asyncio.get_running_loop()
    transport, protocol = await loop.create_datagram_endpoint(
        _Collector,
        local_addr=("0.0.0.0", 0),  # noqa: S104 - nhận phản hồi từ mọi card mạng
        family=socket.AF_INET,
    )
    sock: socket.socket | None = transport.get_extra_info("socket")
    if sock is not None:
        sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 2)
    probes = [build_probe(str(uuid.uuid4()), *t) for t in _PROBE_TYPES]

    def send_multicast(probe: bytes) -> None:
        # Không biết card nào (hoặc không phải Linux): để hệ điều hành chọn theo tuyến mặc định
        choices: list[str | None] = list(if_addrs) or [None]
        for ip in choices:
            try:
                if sock is not None and ip is not None:
                    sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(ip))
                transport.sendto(probe, (MULTICAST_GROUP, port))
            except OSError as e:
                log.warning("Không gửi được Probe multicast qua %s: %s", ip or "card mặc định", e)

    def send_all() -> None:
        for probe in probes:
            send_multicast(probe)
            for ip in unicast:
                try:
                    transport.sendto(probe, (ip, port))
                except OSError as e:
                    log.debug("Không gửi được Probe tới %s: %s", ip, e)

    try:
        send_all()
        # UDP có thể mất gói: gửi lại một lần sau khoảng 1/3 thời gian chờ
        resend_after = min(1.0, timeout_seconds / 3)
        await asyncio.sleep(resend_after)
        send_all()
        await asyncio.sleep(max(0.0, timeout_seconds - resend_after))
    finally:
        transport.close()
    log.info(
        "WS-Discovery tìm thấy %d thiết bị (card: %s; %d địa chỉ unicast)",
        len(protocol.found),
        ", ".join(if_addrs) or "mặc định",
        len(unicast),
    )
    return sorted(protocol.found.values(), key=lambda d: tuple(int(x) if x.isdigit() else 0 for x in d.host.split(".")))
