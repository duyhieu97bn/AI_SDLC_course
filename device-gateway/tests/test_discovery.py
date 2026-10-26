"""Phân tích ProbeMatches, mở rộng mục tiêu IP/CIDR và dò unicast tới bộ giả lập."""

from __future__ import annotations

import asyncio

import pytest

from app.discovery.ws_discovery import expand_targets, normalize_mac, parse_probe_matches, parse_scopes
from tests.conftest import RunningSim

HIKVISION_LIKE = b"""<?xml version="1.0" encoding="UTF-8"?>
<env:Envelope xmlns:env="http://www.w3.org/2003/05/soap-envelope"
  xmlns:wsadis="http://schemas.xmlsoap.org/ws/2004/08/addressing"
  xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery" xmlns:dn="http://www.onvif.org/ver10/network/wsdl">
<env:Header><wsadis:RelatesTo>urn:uuid:1</wsadis:RelatesTo></env:Header>
<env:Body><d:ProbeMatches><d:ProbeMatch>
<wsadis:EndpointReference><wsadis:Address>urn:uuid:4b3a</wsadis:Address></wsadis:EndpointReference>
<d:Types>dn:NetworkVideoTransmitter tds:Device</d:Types>
<d:Scopes>onvif://www.onvif.org/type/video_encoder onvif://www.onvif.org/Profile/Streaming
 onvif://www.onvif.org/name/HIKVISION%20DS-2CD2143G2 onvif://www.onvif.org/hardware/DS-2CD2143G2-I
 onvif://www.onvif.org/MAC/bc-ad-28-11-22-33 onvif://www.onvif.org/location/city/hanoi</d:Scopes>
<d:XAddrs>http://169.254.1.5/onvif/device_service http://192.168.1.64:8000/onvif/device_service</d:XAddrs>
<d:MetadataVersion>10</d:MetadataVersion>
</d:ProbeMatch></d:ProbeMatches></env:Body></env:Envelope>"""


def test_probe_match_prefers_sender_address_and_reads_scopes() -> None:
    (device,) = parse_probe_matches(HIKVISION_LIKE, sender="192.168.1.64")
    assert (device.host, device.port) == ("192.168.1.64", 8000)
    assert device.model == "DS-2CD2143G2-I"
    assert device.hardware == "DS-2CD2143G2-I"
    assert device.manufacturer == "HIKVISION DS-2CD2143G2"  # không có scope manufacturer → dùng name
    assert device.mac_address == "BC:AD:28:11:22:33"
    assert len(device.scopes) == 6


def test_probe_match_without_sender_uses_first_ipv4() -> None:
    (device,) = parse_probe_matches(HIKVISION_LIKE)
    assert (device.host, device.port) == ("169.254.1.5", 80)


def test_garbage_datagram_is_ignored() -> None:
    assert parse_probe_matches(b"\x00\x01garbage") == []
    assert parse_probe_matches(b"<a><b/></a>") == []


def test_scope_parsing_decodes_and_aliases() -> None:
    info = parse_scopes(
        [
            "onvif://www.onvif.org/name/AXIS%20M3046",
            "onvif://www.onvif.org/mfr/Axis",
            "onvif://www.onvif.org/hardware/M3046",
            "http://other/ignored",
        ]
    )
    assert info == {"name": "AXIS M3046", "manufacturer": "Axis", "hardware": "M3046"}


def test_mac_normalization() -> None:
    assert normalize_mac("bcad28112233") == "BC:AD:28:11:22:33"
    assert normalize_mac("bc-ad-28-11-22-33") == "BC:AD:28:11:22:33"
    assert normalize_mac("unknown") == "unknown"


def test_expand_targets_handles_ip_cidr_dedup_and_cap() -> None:
    assert expand_targets(["192.168.1.10", "192.168.1.10/32", " "]) == ["192.168.1.10"]
    assert expand_targets(["10.0.0.0/30"]) == ["10.0.0.1", "10.0.0.2"]
    assert len(expand_targets(["10.1.0.0/22"])) == 1022
    with pytest.raises(ValueError, match="tối đa"):
        expand_targets(["10.0.0.0/21"])
    with pytest.raises(ValueError, match="không hợp lệ"):
        expand_targets(["camera.local"])
    with pytest.raises(ValueError, match="IPv4"):
        expand_targets(["fe80::1"])


def test_unicast_probe_against_simulator(sim: RunningSim) -> None:
    from app.discovery.ws_discovery import discover

    found = asyncio.run(discover(["127.0.0.1"], 0.6, port=sim.config.discovery_port, multicast=False))
    assert [(d.host, d.port, d.model) for d in found] == [("127.0.0.1", sim.port, "SIM-PTZ")]
