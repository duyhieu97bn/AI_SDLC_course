"""WS-Security PasswordDigest và phân loại lỗi SOAP/HTTP thành mã DeviceError."""

from __future__ import annotations

import base64
from datetime import UTC, datetime, timedelta

import httpx
import pytest

from app.onvif import soap
from app.onvif.client import DeviceTarget, OnvifClient
from app.onvif.errors import DeviceError, ErrorCode
from onvif_sim.soap import DigestVerifier, SoapFault
from tests.conftest import RunningSim


def test_password_digest_matches_onvif_programmers_guide_vector() -> None:
    nonce = base64.b64decode("LKqI6G/AikKCQrN0zqZFlg==")
    assert soap.password_digest(nonce, "2010-09-16T07:50:45Z", "userpassword") == "tuOSpGlFlIXsozq4HFNeeGeFLEI="


def test_created_is_utc_with_milliseconds() -> None:
    moment = datetime(2026, 10, 23, 15, 4, 5, 678900, tzinfo=UTC)
    assert soap.format_created(moment) == "2026-10-23T15:04:05.678Z"


def _header(username: str, password: str, moment: datetime) -> bytes:
    return soap.build_envelope("<tds:GetDeviceInformation/>", soap.security_header(username, password, moment))


def test_security_header_is_accepted_by_independent_verifier() -> None:
    now = datetime.now(UTC)
    root = soap.parse_xml(_header("admin", "p@ss<&>", now))
    verifier = DigestVerifier()
    assert verifier.verify(root.find("{*}Header"), {"admin": "p@ss<&>"}, now) == "admin"
    # Cùng nonce gửi lại bị từ chối (chống replay)
    with pytest.raises(SoapFault):
        verifier.verify(root.find("{*}Header"), {"admin": "p@ss<&>"}, now)


def test_verifier_rejects_wrong_password_and_clock_skew() -> None:
    now = datetime.now(UTC)
    verifier = DigestVerifier()
    with pytest.raises(SoapFault):
        verifier.verify(soap.parse_xml(_header("admin", "sai", now)).find("{*}Header"), {"admin": "dung"}, now)
    skewed = soap.parse_xml(_header("admin", "dung", now - timedelta(hours=1))).find("{*}Header")
    with pytest.raises(SoapFault):
        verifier.verify(skewed, {"admin": "dung"}, now)


def _fault(subcode: str, reason: str = "Lý do") -> bytes:
    return (
        '<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:ter="http://www.onvif.org/ver10/error">'
        "<s:Body><s:Fault><s:Code><s:Value>s:Sender</s:Value><s:Subcode><s:Value>"
        f"{subcode}</s:Value></s:Subcode></s:Code><s:Reason><s:Text>{reason}</s:Text></s:Reason>"
        "</s:Fault></s:Body></s:Envelope>"
    ).encode()


@pytest.mark.parametrize(
    ("status", "content", "code"),
    [
        (400, _fault("ter:NotAuthorized", "Sender not Authorized"), ErrorCode.AUTH_FAILED),
        (500, _fault("ter:ActionNotSupported"), ErrorCode.NOT_SUPPORTED),
        (400, _fault("ter:InvalidArgVal", "Brightness ngoài phạm vi"), ErrorCode.DEVICE_ERROR),
        (401, b"<html>Unauthorized</html>", ErrorCode.AUTH_FAILED),
        (200, b"<html><body>Router login</body></html>", ErrorCode.NOT_ONVIF),
        (200, b"not xml at all", ErrorCode.NOT_ONVIF),
        (404, b"", ErrorCode.NOT_ONVIF),
        (500, b'<?xml version="1.0"?><root/>', ErrorCode.NOT_ONVIF),
    ],
)
def test_parse_response_maps_errors(status: int, content: bytes, code: ErrorCode) -> None:
    with pytest.raises(DeviceError) as err:
        soap.parse_response(status, content)
    assert err.value.code == code


def test_device_error_keeps_fault_reason() -> None:
    with pytest.raises(DeviceError) as err:
        soap.parse_response(400, _fault("ter:InvalidArgVal", "Brightness ngoài phạm vi"))
    assert err.value.detail == "Brightness ngoài phạm vi"


def test_soap11_fault_is_understood() -> None:
    content = (
        b'<SOAP-ENV:Envelope xmlns:SOAP-ENV="http://schemas.xmlsoap.org/soap/envelope/"><SOAP-ENV:Body>'
        b"<SOAP-ENV:Fault><faultcode>SOAP-ENV:Client</faultcode><faultstring>Not Authorized</faultstring>"
        b"</SOAP-ENV:Fault></SOAP-ENV:Body></SOAP-ENV:Envelope>"
    )
    with pytest.raises(DeviceError) as err:
        soap.parse_response(500, content)
    assert err.value.code == ErrorCode.AUTH_FAILED


def test_entity_expansion_is_blocked() -> None:
    bomb = b'<?xml version="1.0"?><!DOCTYPE x [<!ENTITY a "aaaa">]><s:Envelope xmlns:s="x">&a;</s:Envelope>'
    with pytest.raises(DeviceError) as err:
        soap.parse_response(200, bomb)
    assert err.value.code == ErrorCode.NOT_ONVIF


def _client(handler: httpx.MockTransport) -> OnvifClient:
    return OnvifClient(DeviceTarget("10.0.0.9", 80, "admin", "x"), httpx.AsyncClient(transport=handler), timeout=1)


@pytest.mark.anyio
@pytest.mark.parametrize(
    "exc", [httpx.ConnectError("refused"), httpx.ConnectTimeout("timeout"), httpx.ReadTimeout("slow")]
)
async def test_transport_failures_are_unreachable(exc: Exception) -> None:
    def fail(request: httpx.Request) -> httpx.Response:
        raise exc

    with pytest.raises(DeviceError) as err:
        await _client(httpx.MockTransport(fail)).get_device_information()
    assert err.value.code == ErrorCode.UNREACHABLE


@pytest.mark.anyio
async def test_password_never_sent_in_clear(sim: RunningSim) -> None:
    seen: list[bytes] = []

    def capture(request: httpx.Request) -> httpx.Response:
        seen.append(request.content)
        return httpx.Response(401)

    with pytest.raises(DeviceError):
        await OnvifClient(
            DeviceTarget("h", 80, "admin", "BiMat-123"), httpx.AsyncClient(transport=httpx.MockTransport(capture))
        ).get_device_information()
    assert seen and b"BiMat-123" not in seen[0]
    assert b"#PasswordDigest" in seen[0]


@pytest.mark.anyio
async def test_clock_offset_is_compensated(sim: RunningSim) -> None:
    async with httpx.AsyncClient() as http:
        target = DeviceTarget("127.0.0.1", sim.port, sim.config.user, sim.config.password)
        # Không đồng bộ giờ: đồng hồ thiết bị lệch +1 giờ nên digest bị từ chối
        with pytest.raises(DeviceError) as err:
            await OnvifClient(target, http).get_device_information()
        assert err.value.code == ErrorCode.AUTH_FAILED
        client = OnvifClient(target, http)
        await client.connect()
        assert 3590 < client.clock_offset.total_seconds() < 3610
        assert (await client.get_device_information()).model == "SIM-PTZ"


@pytest.fixture
def anyio_backend() -> str:
    return "asyncio"
