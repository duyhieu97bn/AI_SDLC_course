import asyncio
import socket

from site_connector.discovery import Device, discover, expand_targets, parse_probe_matches

PROBE_MATCH = b"""<?xml version="1.0" encoding="UTF-8"?>
<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery">
<s:Body><d:ProbeMatches><d:ProbeMatch>
<d:Scopes>onvif://www.onvif.org/name/Site%20Cam onvif://www.onvif.org/hardware/DS-2CD2143 onvif://www.onvif.org/mfr/Hikvision onvif://www.onvif.org/mac/020000000031</d:Scopes>
<d:XAddrs>http://[fe80::1]/onvif/device_service http://127.0.0.1:8080/onvif/device_service</d:XAddrs>
</d:ProbeMatch></d:ProbeMatches></s:Body></s:Envelope>"""


def test_parse_probe_matches() -> None:
    devices = parse_probe_matches(PROBE_MATCH, "127.0.0.1")
    assert devices == [
        Device(
            host="127.0.0.1",
            port=8080,
            manufacturer="Hikvision",
            model="DS-2CD2143",
            macAddress="02:00:00:00:00:31",
            hardware="DS-2CD2143",
            scopes=devices[0].scopes,
        )
    ]
    assert devices[0].to_json()["macAddress"] == "02:00:00:00:00:31"


def test_garbage_is_ignored() -> None:
    assert parse_probe_matches(b"<not-xml") == []
    assert parse_probe_matches(b"<a/>") == []


def test_expand_targets() -> None:
    assert expand_targets(["10.0.0.0/30", "10.0.0.1"]) == ["10.0.0.1", "10.0.0.2"]


def test_discover_unicast_against_fake_device() -> None:
    async def scenario() -> list[Device]:
        loop = asyncio.get_running_loop()

        class Responder(asyncio.DatagramProtocol):
            def connection_made(self, transport: asyncio.BaseTransport) -> None:
                self.transport = transport

            def datagram_received(self, data: bytes, addr: tuple[str, int]) -> None:
                if b"Probe" in data:
                    self.transport.sendto(PROBE_MATCH, addr)  # type: ignore[attr-defined]

        transport, _ = await loop.create_datagram_endpoint(
            Responder, local_addr=("127.0.0.1", 0), family=socket.AF_INET
        )
        port = transport.get_extra_info("sockname")[1]
        try:
            return await discover(0.5, ["127.0.0.1"], port=port, interfaces=["127.0.0.1"])
        finally:
            transport.close()

    devices = asyncio.run(scenario())
    assert [(d.host, d.port, d.manufacturer) for d in devices] == [("127.0.0.1", 8080, "Hikvision")]
