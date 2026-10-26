"""WS-Discovery: gửi Probe multicast 239.255.255.250:3702 và Probe unicast tới từng IP mục tiêu.

Unicast dùng khi mạng chặn multicast (khác VLAN, Docker bridge, VPN). Thiết bị trả ProbeMatches về
đúng cổng nguồn của gói Probe, nên chỉ cần một socket UDP tạm.
"""

from __future__ import annotations

import asyncio
import ipaddress
import logging
import socket
import uuid
from urllib.parse import unquote, urlsplit

from app.models import DiscoveredDevice
from app.onvif.errors import DeviceError
from app.onvif.soap import parse_xml, text_of

log = logging.getLogger(__name__)

MULTICAST_GROUP = "239.255.255.250"
DEFAULT_PORT = 3702
MAX_TARGETS = 1024
_SCOPE_PREFIX = "onvif://www.onvif.org/"
_PROBE_TYPES = (
    ("dn", "http://www.onvif.org/ver10/network/wsdl", "NetworkVideoTransmitter"),
    ("tds", "http://www.onvif.org/ver10/device/wsdl", "Device"),
)


def expand_targets(targets: list[str], limit: int = MAX_TARGETS) -> list[str]:
    """Đổi danh sách IP/CIDR thành danh sách IPv4 (tối đa ``limit``). Sai định dạng thì ``ValueError``."""
    result: list[str] = []
    seen: set[str] = set()
    for raw in targets:
        value = raw.strip()
        if not value:
            continue
        try:
            network = ipaddress.ip_network(value, strict=False)
        except ValueError as e:
            raise ValueError(f"Mục tiêu không hợp lệ: '{value}' (cần IP hoặc CIDR IPv4)") from e
        if network.version != 4:
            raise ValueError(f"Chỉ hỗ trợ IPv4: '{value}'")
        hosts = [network.network_address] if network.num_addresses == 1 else network.hosts()
        for ip in hosts:
            text = str(ip)
            if text in seen:
                continue
            if len(result) >= limit:
                raise ValueError(f"Quá nhiều địa chỉ cần dò (tối đa {limit})")
            seen.add(text)
            result.append(text)
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
    """Lấy name/hardware/manufacturer/mac từ scope ``onvif://www.onvif.org/<loại>/<giá trị>``."""
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
        port = parts.port or (443 if parts.scheme == "https" else 80)
        parsed.append((parts.hostname, port))
    if not parsed:
        return None
    for host, port in parsed:
        if host == sender:
            return host, port
    ipv4 = [p for p in parsed if ":" not in p[0]]
    return (ipv4 or parsed)[0]


def parse_probe_matches(data: bytes, sender: str | None = None) -> list[DiscoveredDevice]:
    """Đọc gói ProbeMatches; gói hỏng hoặc không phải ProbeMatches trả danh sách rỗng."""
    try:
        root = parse_xml(data)
    except DeviceError:
        return []
    devices = []
    for match in root.iterfind(".//{*}ProbeMatch"):
        xaddrs = (text_of(match, "{*}XAddrs", "") or "").split()
        scopes = (text_of(match, "{*}Scopes", "") or "").split()
        address = _pick_xaddr(xaddrs, sender)
        if address is None:
            continue
        info = parse_scopes(scopes)
        devices.append(
            DiscoveredDevice(
                host=address[0],
                port=address[1],
                manufacturer=info.get("manufacturer") or info.get("name"),
                model=info.get("hardware") or info.get("name"),
                mac_address=normalize_mac(info["mac"]) if "mac" in info else None,
                hardware=info.get("hardware"),
                scopes=scopes,
            )
        )
    return devices


class _Collector(asyncio.DatagramProtocol):
    def __init__(self) -> None:
        self.found: dict[str, DiscoveredDevice] = {}

    def datagram_received(self, data: bytes, addr: tuple[str | object, ...]) -> None:
        sender = addr[0] if addr and isinstance(addr[0], str) else None
        for device in parse_probe_matches(data, sender):
            existing = self.found.get(device.host)
            if existing is None:
                self.found[device.host] = device
            else:
                # Gộp phản hồi cho Probe "NetworkVideoTransmitter" và "Device" của cùng thiết bị
                existing.scopes = list(dict.fromkeys(existing.scopes + device.scopes))
                existing.manufacturer = existing.manufacturer or device.manufacturer
                existing.model = existing.model or device.model
                existing.mac_address = existing.mac_address or device.mac_address
                existing.hardware = existing.hardware or device.hardware

    def error_received(self, exc: Exception) -> None:
        log.debug("Lỗi UDP khi dò WS-Discovery: %s", exc)


async def discover(
    targets: list[str],
    timeout_seconds: float,
    *,
    port: int = DEFAULT_PORT,
    multicast: bool = True,
) -> list[DiscoveredDevice]:
    """Dò thiết bị ONVIF trong ``timeout_seconds`` giây, khử trùng theo host."""
    unicast = expand_targets(targets)
    loop = asyncio.get_running_loop()
    transport, protocol = await loop.create_datagram_endpoint(
        _Collector,
        local_addr=("0.0.0.0", 0),  # noqa: S104 - nhận phản hồi từ mọi card mạng
        family=socket.AF_INET,
    )
    sock = transport.get_extra_info("socket")
    if sock is not None:
        sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 2)
    probes = [build_probe(str(uuid.uuid4()), *t) for t in _PROBE_TYPES]

    def send_all() -> None:
        for probe in probes:
            if multicast:
                try:
                    transport.sendto(probe, (MULTICAST_GROUP, port))
                except OSError as e:
                    log.warning("Không gửi được Probe multicast: %s", e)
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
    log.info("WS-Discovery tìm thấy %d thiết bị (%d địa chỉ unicast)", len(protocol.found), len(unicast))
    return sorted(protocol.found.values(), key=lambda d: tuple(int(x) if x.isdigit() else 0 for x in d.host.split(".")))
