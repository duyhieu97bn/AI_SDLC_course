"""Trả lời WS-Discovery Probe trên UDP (multicast 239.255.255.250 và unicast) bằng ProbeMatches."""

from __future__ import annotations

import asyncio
import logging
import socket
import struct
import uuid
from typing import cast
from urllib.parse import quote
from xml.etree.ElementTree import ParseError  # noqa: S405 - chỉ dùng kiểu ngoại lệ

from defusedxml import ElementTree as SafeET

from onvif_sim.config import SimConfig
from onvif_sim.soap import esc, local

log = logging.getLogger("onvif_sim.discovery")

MULTICAST_GROUP = "239.255.255.250"
_ACCEPTED_TYPES = ("NetworkVideoTransmitter", "Device")


def probe_message_id(data: bytes) -> str | None:
    """MessageID của gói Probe phù hợp kiểu thiết bị, hoặc ``None`` nếu không cần trả lời."""
    try:
        root = SafeET.fromstring(data)
    except (ParseError, ValueError):
        return None
    probe = root.find("{*}Body/{*}Probe")
    if probe is None:
        return None
    types = (probe.findtext("{*}Types") or "").split()
    if types and not any(local(t.rsplit(":", 1)[-1]) in _ACCEPTED_TYPES for t in types):
        return None
    return (root.findtext("{*}Header/{*}MessageID") or "").strip() or "urn:uuid:unknown"


def probe_matches(config: SimConfig, relates_to: str) -> bytes:
    scopes = " ".join(
        [
            "onvif://www.onvif.org/type/video_encoder",
            "onvif://www.onvif.org/type/ptz" if config.ptz else "onvif://www.onvif.org/type/Network_Video_Transmitter",
            "onvif://www.onvif.org/Profile/Streaming",
            "onvif://www.onvif.org/Profile/T",
            *(["onvif://www.onvif.org/Profile/G"] if config.is_recorder else []),
            f"onvif://www.onvif.org/name/{quote(config.name)}",
            f"onvif://www.onvif.org/hardware/{quote(config.model)}",
            f"onvif://www.onvif.org/manufacturer/{quote(config.manufacturer)}",
            f"onvif://www.onvif.org/mac/{quote(config.mac)}",
            "onvif://www.onvif.org/location/country/vietnam",
        ]
    )
    return (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" '
        'xmlns:a="http://schemas.xmlsoap.org/ws/2004/08/addressing" '
        'xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery" '
        'xmlns:dn="http://www.onvif.org/ver10/network/wsdl" xmlns:tds="http://www.onvif.org/ver10/device/wsdl">'
        "<s:Header>"
        f"<a:MessageID>urn:uuid:{uuid.uuid4()}</a:MessageID><a:RelatesTo>{esc(relates_to)}</a:RelatesTo>"
        "<a:To>http://schemas.xmlsoap.org/ws/2004/08/addressing/role/anonymous</a:To>"
        "<a:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/ProbeMatches</a:Action>"
        "</s:Header><s:Body><d:ProbeMatches><d:ProbeMatch>"
        f"<a:EndpointReference><a:Address>urn:uuid:{config.endpoint_uuid}</a:Address></a:EndpointReference>"
        "<d:Types>dn:NetworkVideoTransmitter tds:Device</d:Types>"
        f"<d:Scopes>{esc(scopes)}</d:Scopes>"
        f"<d:XAddrs>{esc(config.base_url)}/onvif/device_service</d:XAddrs>"
        "<d:MetadataVersion>1</d:MetadataVersion>"
        "</d:ProbeMatch></d:ProbeMatches></s:Body></s:Envelope>"
    ).encode()


class _Protocol(asyncio.DatagramProtocol):
    def __init__(self, config: SimConfig) -> None:
        self.config = config
        self.transport: asyncio.DatagramTransport | None = None

    def connection_made(self, transport: asyncio.BaseTransport) -> None:
        # uvloop có lớp transport UDP riêng, không kế thừa asyncio.DatagramTransport nên không kiểm tra isinstance
        self.transport = cast(asyncio.DatagramTransport, transport)

    def datagram_received(self, data: bytes, addr: tuple[str | object, ...]) -> None:
        message_id = probe_message_id(data)
        if message_id is None or self.transport is None:
            return
        log.debug("Nhận Probe từ %s", addr)
        self.transport.sendto(probe_matches(self.config, message_id), addr)


class DiscoveryResponder:
    def __init__(self, config: SimConfig, bind_host: str = "0.0.0.0") -> None:  # noqa: S104 - nghe mọi card mạng
        self.config = config
        self.bind_host = bind_host
        self._transport: asyncio.DatagramTransport | None = None

    async def start(self) -> None:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind((self.bind_host, self.config.discovery_port))
        try:
            membership = socket.inet_aton(MULTICAST_GROUP) + struct.pack("=I", socket.INADDR_ANY)
            sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, membership)
        except OSError as e:
            # Mạng không hỗ trợ multicast (một số Docker network): vẫn trả lời Probe unicast
            log.warning("Không tham gia được nhóm multicast %s: %s", MULTICAST_GROUP, e)
        sock.setblocking(False)
        loop = asyncio.get_running_loop()
        transport, _ = await loop.create_datagram_endpoint(lambda: _Protocol(self.config), sock=sock)
        self._transport = transport
        log.info("WS-Discovery nghe UDP %s:%d", self.bind_host, self.config.discovery_port)

    def stop(self) -> None:
        if self._transport is not None:
            self._transport.close()
            self._transport = None
