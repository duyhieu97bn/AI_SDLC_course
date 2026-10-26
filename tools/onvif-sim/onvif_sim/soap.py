"""Tiện ích SOAP phía giả lập: đọc yêu cầu, kiểm tra WS-Security PasswordDigest, dựng phản hồi/Fault."""

from __future__ import annotations

import base64
import hashlib
import hmac
import re
import time
from collections import OrderedDict
from datetime import UTC, datetime
from xml.etree.ElementTree import Element, ParseError  # noqa: S405 - chỉ dùng kiểu; phân tích qua defusedxml
from xml.sax.saxutils import escape, quoteattr

from defusedxml import ElementTree as SafeET

NS = {
    "s": "http://www.w3.org/2003/05/soap-envelope",
    "ter": "http://www.onvif.org/ver10/error",
    "tds": "http://www.onvif.org/ver10/device/wsdl",
    "trt": "http://www.onvif.org/ver10/media/wsdl",
    "tptz": "http://www.onvif.org/ver20/ptz/wsdl",
    "timg": "http://www.onvif.org/ver20/imaging/wsdl",
    "tev": "http://www.onvif.org/ver10/events/wsdl",
    "tan": "http://www.onvif.org/ver20/analytics/wsdl",
    "tr2": "http://www.onvif.org/ver20/media/wsdl",
    "trc": "http://www.onvif.org/ver10/recording/wsdl",
    "tse": "http://www.onvif.org/ver10/search/wsdl",
    "trp": "http://www.onvif.org/ver10/replay/wsdl",
    "axt": "http://www.onvif.org/ver20/analytics",
    "tt": "http://www.onvif.org/ver10/schema",
    "wsnt": "http://docs.oasis-open.org/wsn/b-2",
    "wsa": "http://www.w3.org/2005/08/addressing",
    "tns1": "http://www.onvif.org/ver10/topics",
    "sim": "urn:camhub:onvif-sim",
}
_NS_DECL = " ".join(f'xmlns:{p}="{u}"' for p, u in NS.items())
SOAP_CONTENT_TYPE = "application/soap+xml; charset=utf-8"
_DURATION = re.compile(r"^P(?:T(?:(?P<h>\d+(?:\.\d+)?)H)?(?:(?P<m>\d+(?:\.\d+)?)M)?(?:(?P<s>\d+(?:\.\d+)?)S)?)?$")


class SoapFault(Exception):  # noqa: N818 - đặt theo tên khái niệm SOAP Fault
    """Lỗi trả về dạng SOAP Fault. Sender → HTTP 400, Receiver → HTTP 500 (ONVIF Core 5.11)."""

    def __init__(self, code: str, subcode: str, reason: str) -> None:
        super().__init__(reason)
        self.code = code
        self.subcode = subcode
        self.reason = reason

    @property
    def http_status(self) -> int:
        return 400 if self.code == "Sender" else 500


def not_authorized() -> SoapFault:
    return SoapFault("Sender", "ter:NotAuthorized", "Sender not Authorized")


def invalid_arg(reason: str) -> SoapFault:
    return SoapFault("Sender", "ter:InvalidArgVal", reason)


def action_not_supported(op: str) -> SoapFault:
    return SoapFault("Receiver", "ter:ActionNotSupported", f"Optional Action Not Implemented: {op}")


def esc(value: object) -> str:
    return escape(str(value))


def attr(value: object) -> str:
    return quoteattr(str(value))


def local(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def txt(el: Element | None, path: str, default: str | None = None) -> str | None:
    if el is None:
        return default
    found = el.find(path)
    if found is None or found.text is None:
        return default
    return found.text.strip()


def parse_request(content: bytes) -> tuple[Element | None, Element]:
    """Trả (Header, phần tử thao tác đầu tiên trong Body)."""
    try:
        root = SafeET.fromstring(content)
    except (ParseError, ValueError) as e:
        raise SoapFault("Sender", "ter:WellFormed", "XML không hợp lệ") from e
    body = root.find("{*}Body")
    if local(root.tag) != "Envelope" or body is None or len(body) == 0:
        raise SoapFault("Sender", "ter:WellFormed", "Không phải SOAP Envelope")
    return root.find("{*}Header"), body[0]


def envelope(body: str) -> bytes:
    return (
        f'<?xml version="1.0" encoding="UTF-8"?><s:Envelope {_NS_DECL}><s:Header/><s:Body>{body}</s:Body></s:Envelope>'
    ).encode()


def fault_envelope(fault: SoapFault) -> bytes:
    return envelope(
        f"<s:Fault><s:Code><s:Value>s:{fault.code}</s:Value><s:Subcode><s:Value>{esc(fault.subcode)}</s:Value>"
        f'</s:Subcode></s:Code><s:Reason><s:Text xml:lang="en">{esc(fault.reason)}</s:Text></s:Reason></s:Fault>'
    )


def parse_datetime(raw: str) -> datetime | None:
    try:
        moment = datetime.fromisoformat(raw.strip())
    except ValueError:
        return None
    return moment if moment.tzinfo else moment.replace(tzinfo=UTC)


def iso(moment: datetime) -> str:
    return moment.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_duration(raw: str | None, default: float) -> float:
    if not raw:
        return default
    match = _DURATION.match(raw.strip())
    if not match:
        return default
    hours, minutes, seconds = (float(match.group(g) or 0) for g in ("h", "m", "s"))
    return hours * 3600 + minutes * 60 + seconds


class DigestVerifier:
    """Kiểm tra UsernameToken: digest đúng, ``Created`` lệch ≤ 5 phút so với đồng hồ thiết bị, nonce không lặp."""

    def __init__(self, window_seconds: float = 300.0, max_nonces: int = 4096) -> None:
        self._window = window_seconds
        self._nonces: OrderedDict[str, float] = OrderedDict()
        self._max = max_nonces

    def verify(self, header: Element | None, users: dict[str, str], device_now: datetime) -> str:
        token = header.find(".//{*}Security/{*}UsernameToken") if header is not None else None
        if token is None:
            raise not_authorized()
        username = txt(token, "{*}Username", "") or ""
        password_el = token.find("{*}Password")
        expected = users.get(username)
        if expected is None or password_el is None or password_el.text is None:
            raise not_authorized()
        supplied = password_el.text.strip()
        if password_el.get("Type", "").endswith("#PasswordText"):
            if not hmac.compare_digest(supplied, expected):
                raise not_authorized()
            return username
        nonce_b64 = txt(token, "{*}Nonce", "") or ""
        created = txt(token, "{*}Created", "") or ""
        moment = parse_datetime(created)
        if moment is None or abs((moment - device_now).total_seconds()) > self._window:
            raise not_authorized()
        try:
            nonce = base64.b64decode(nonce_b64, validate=True)
        except ValueError as e:
            raise not_authorized() from e
        raw = nonce + created.encode() + expected.encode()
        digest = base64.b64encode(hashlib.sha1(raw, usedforsecurity=False).digest()).decode()
        if not hmac.compare_digest(digest, supplied):
            raise not_authorized()
        if nonce_b64 in self._nonces:
            raise not_authorized()
        self._nonces[nonce_b64] = time.monotonic()
        while len(self._nonces) > self._max:
            self._nonces.popitem(last=False)
        return username
