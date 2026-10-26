"""Dựng phong bì SOAP 1.2, WS-Security UsernameToken (PasswordDigest) và đọc SOAP Fault."""

from __future__ import annotations

import base64
import hashlib
import secrets
from datetime import UTC, datetime
from xml.etree import ElementTree as ET  # noqa: S405 - chỉ dựng/serialize; phân tích qua defusedxml
from xml.etree.ElementTree import Element, ParseError  # noqa: S405
from xml.sax.saxutils import escape, quoteattr

from defusedxml import ElementTree as SafeET

from app.onvif.errors import DeviceError, ErrorCode

SOAP12_ENV = "http://www.w3.org/2003/05/soap-envelope"
SOAP11_ENV = "http://schemas.xmlsoap.org/soap/envelope/"

NAMESPACES: dict[str, str] = {
    "s": SOAP12_ENV,
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
    "wsse": "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd",
    "wsu": "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd",
}

# URI kiểu mật khẩu theo chuẩn, không phải mật khẩu
_WSS_PROFILE = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0"
PASSWORD_DIGEST_TYPE = _WSS_PROFILE + "#PasswordDigest"
BASE64_ENCODING = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary"

for _prefix, _uri in NAMESPACES.items():
    ET.register_namespace(_prefix, _uri)

_NS_DECL = " ".join(f'xmlns:{prefix}="{uri}"' for prefix, uri in NAMESPACES.items())

# Mã lỗi con (subcode) nghĩa là sai tài khoản / không đủ quyền
_AUTH_SUBCODES = {
    "NotAuthorized",
    "FailedAuthentication",
    "InvalidSecurity",
    "InvalidSecurityToken",
    "FailedCheck",
    "SecurityTokenUnavailable",
    "MessageExpired",
}
_UNSUPPORTED_SUBCODES = {"ActionNotSupported", "NotSupported", "NoSuchService"}
_AUTH_REASON_HINTS = ("not authorized", "notauthorized", "authority failure", "unauthorized", "authentication fail")


def esc(value: object) -> str:
    """Thoát ký tự đặc biệt XML cho giá trị chèn vào phần tử."""
    return escape(str(value))


def attr(value: object) -> str:
    """Giá trị thuộc tính XML đã kèm dấu nháy."""
    return quoteattr(str(value))


def local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def strip_prefix(qname: str) -> str:
    return qname.strip().rsplit(":", 1)[-1]


def format_created(moment: datetime) -> str:
    """Thời điểm UTC dạng ``2026-10-23T08:15:30.123Z`` cho ``wsu:Created``."""
    moment = moment.astimezone(UTC)
    return moment.strftime("%Y-%m-%dT%H:%M:%S.") + f"{moment.microsecond // 1000:03d}Z"


def password_digest(nonce: bytes, created: str, password: str) -> str:
    """``Base64(SHA-1(nonce + created + password))`` theo WS-Security UsernameToken Profile 1.0."""
    raw = nonce + created.encode("utf-8") + password.encode("utf-8")
    # SHA-1 là bắt buộc theo chuẩn, không phải lựa chọn của chúng ta
    return base64.b64encode(hashlib.sha1(raw, usedforsecurity=False).digest()).decode("ascii")


def security_header(username: str, password: str, moment: datetime, nonce: bytes | None = None) -> str:
    nonce = nonce if nonce is not None else secrets.token_bytes(16)
    created = format_created(moment)
    digest = password_digest(nonce, created, password)
    return (
        '<wsse:Security s:mustUnderstand="1"><wsse:UsernameToken>'
        f"<wsse:Username>{esc(username)}</wsse:Username>"
        f'<wsse:Password Type="{PASSWORD_DIGEST_TYPE}">{digest}</wsse:Password>'
        f'<wsse:Nonce EncodingType="{BASE64_ENCODING}">{base64.b64encode(nonce).decode("ascii")}</wsse:Nonce>'
        f"<wsu:Created>{created}</wsu:Created>"
        "</wsse:UsernameToken></wsse:Security>"
    )


def build_envelope(body: str, header: str = "") -> bytes:
    return (
        f'<?xml version="1.0" encoding="UTF-8"?><s:Envelope {_NS_DECL}>'
        f"<s:Header>{header}</s:Header><s:Body>{body}</s:Body></s:Envelope>"
    ).encode()


def parse_xml(content: bytes) -> Element:
    """Phân tích XML không tin cậy (chặn entity expansion/XXE)."""
    try:
        return SafeET.fromstring(content)
    except (ParseError, ValueError) as e:
        raise DeviceError(ErrorCode.NOT_ONVIF, "Thiết bị không trả về XML hợp lệ") from e


def parse_response(status: int, content: bytes) -> Element:
    """Trả về ``Body`` của phản hồi SOAP, hoặc ném ``DeviceError`` đã phân loại."""
    if status == 401:
        raise DeviceError(ErrorCode.AUTH_FAILED, "Sai tên đăng nhập hoặc mật khẩu thiết bị (HTTP 401)")
    if not content.strip():
        raise DeviceError(ErrorCode.NOT_ONVIF, f"Thiết bị trả HTTP {status} không có nội dung SOAP")
    try:
        root = SafeET.fromstring(content)
    except (ParseError, ValueError) as e:
        raise DeviceError(ErrorCode.NOT_ONVIF, f"Phản hồi không phải SOAP (HTTP {status})") from e
    if local_name(root.tag) != "Envelope" or not root.tag.startswith(("{" + SOAP12_ENV, "{" + SOAP11_ENV)):
        raise DeviceError(ErrorCode.NOT_ONVIF, f"Phản hồi không phải SOAP (HTTP {status})")
    body = root.find("{*}Body")
    if body is None:
        raise DeviceError(ErrorCode.NOT_ONVIF, "Phản hồi SOAP thiếu Body")
    fault = body.find("{*}Fault")
    if fault is not None:
        raise fault_to_error(fault)
    if status >= 400:
        raise DeviceError(ErrorCode.DEVICE_ERROR, f"Thiết bị trả HTTP {status}")
    return body


def fault_codes(fault: Element) -> list[str]:
    """Mọi giá trị Code/Subcode (SOAP 1.2) hoặc faultcode (SOAP 1.1), đã bỏ tiền tố namespace."""
    return [
        strip_prefix(el.text)
        for el in fault.iter()
        if local_name(el.tag) in ("Value", "faultcode") and el.text and el.text.strip()
    ]


def fault_reason(fault: Element) -> str:
    for name in ("Text", "faultstring"):
        for el in fault.iter():
            if local_name(el.tag) == name and el.text and el.text.strip():
                return el.text.strip()
    return ""


def fault_to_error(fault: Element) -> DeviceError:
    codes = fault_codes(fault)
    reason = fault_reason(fault)
    if any(code in _AUTH_SUBCODES for code in codes) or any(h in reason.lower() for h in _AUTH_REASON_HINTS):
        return DeviceError(ErrorCode.AUTH_FAILED, reason or "Thiết bị từ chối tài khoản")
    if any(code in _UNSUPPORTED_SUBCODES for code in codes):
        return DeviceError(ErrorCode.NOT_SUPPORTED, reason or "Thiết bị không hỗ trợ thao tác này")
    return DeviceError(ErrorCode.DEVICE_ERROR, reason or "/".join(codes) or "SOAP Fault không rõ lý do")


def text_of(el: Element | None, path: str, default: str | None = None) -> str | None:
    """Text của phần tử con theo đường dẫn ``{*}`` (bỏ qua namespace), đã ``strip``."""
    if el is None:
        return default
    found = el.find(path)
    if found is None or found.text is None:
        return default
    return found.text.strip()


def int_of(el: Element | None, path: str, default: int = 0) -> int:
    raw = text_of(el, path)
    if raw is None:
        return default
    try:
        return int(float(raw))
    except ValueError:
        return default


def float_of(el: Element | None, path: str) -> float | None:
    raw = text_of(el, path)
    if raw is None:
        return None
    try:
        return float(raw)
    except ValueError:
        return None


def serialize(el: Element) -> str:
    """Chuỗi XML của một phần tử (kèm khai báo namespace cần thiết)."""
    return ET.tostring(el, encoding="unicode")


def serialize_as(el: Element, prefix: str, name: str) -> str:
    """Serialize phần tử nhưng đổi tên gốc, ví dụ ``trt:Configurations`` → ``trt:Configuration``."""
    original = el.tag
    el.tag = f"{{{NAMESPACES[prefix]}}}{name}"
    try:
        return serialize(el)
    finally:
        el.tag = original


def set_child_text(el: Element, path: list[str], value: str) -> None:
    """Gán text cho phần tử con theo tên cục bộ, tạo phần tử ``tt:`` nếu chưa có."""
    node = el
    for name in path:
        child = next((c for c in node if local_name(c.tag) == name), None)
        if child is None:
            child = ET.SubElement(node, f"{{{NAMESPACES['tt']}}}{name}")
        node = child
    node.text = value


def ensure_h264(config: Element) -> None:
    """Thêm khối ``tt:H264`` (đặt trước Multicast theo lược đồ) khi chuyển sang H.264."""
    if any(local_name(c.tag) == "H264" for c in config):
        return
    tt = NAMESPACES["tt"]
    h264 = ET.Element(f"{{{tt}}}H264")
    ET.SubElement(h264, f"{{{tt}}}GovLength").text = "50"
    ET.SubElement(h264, f"{{{tt}}}H264Profile").text = "Main"
    children = list(config)
    index = next(
        (i for i, c in enumerate(children) if local_name(c.tag) in ("Multicast", "SessionTimeout", "Extension")),
        len(children),
    )
    config.insert(index, h264)


def bool_text(raw: str | None) -> bool | None:
    if raw is None:
        return None
    value = raw.strip().lower()
    if value in ("true", "1", "on", "yes", "active"):
        return True
    if value in ("false", "0", "off", "no", "inactive"):
        return False
    return None
