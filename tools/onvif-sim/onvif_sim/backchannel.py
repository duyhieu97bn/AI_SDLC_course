"""Máy chủ RTSP backchannel âm thanh (ONVIF Streaming Spec, mục 5.3) của camera giả lập (US-15).

Chỉ phục vụ luồng gửi xuống loa camera: client phải gửi ``Require: www.onvif.org/ver20/backchannel`` khi DESCRIBE
(thiếu thì trả 551 Option not supported như camera thật). SDP có một track âm thanh ``a=sendonly`` G.711 μ-law
8 kHz; chỉ nhận RTP xen kẽ trên TCP (``RTP/AVP/TCP;interleaved``), UDP trả 461. Xác thực Digest MD5 bằng tài khoản
ONVIF của bộ giả lập. Số gói/byte RTP nhận được xem ở ``GET /sim/talk`` để kiểm thử.
"""

from __future__ import annotations

import asyncio
import contextlib
import hashlib
import logging
import re
import secrets
import time
from collections.abc import Callable
from dataclasses import dataclass

log = logging.getLogger(__name__)

REQUIRE_BACKCHANNEL = "www.onvif.org/ver20/backchannel"
REALM = "onvif-sim"
SDP = (
    "v=0\r\n"
    "o=- 0 0 IN IP4 0.0.0.0\r\n"
    "s=CamHub Sim Backchannel\r\n"
    "t=0 0\r\n"
    "m=audio 0 RTP/AVP 0\r\n"
    "a=control:trackID=talk\r\n"
    "a=rtpmap:0 PCMU/8000\r\n"
    "a=sendonly\r\n"
)
_AUTH_PARAM = re.compile(r'(\w+)=(?:"([^"]*)"|([^,\s]*))')


@dataclass
class TalkStats:
    sessions: int = 0
    active: bool = False
    packets: int = 0
    bytes: int = 0
    payload_type: int | None = None
    last_duration_seconds: float = 0.0
    started_at: float | None = None

    def as_dict(self) -> dict[str, object]:
        return {
            "sessions": self.sessions,
            "active": self.active,
            "packets": self.packets,
            "bytes": self.bytes,
            "payloadType": self.payload_type,
            "lastDurationSeconds": round(self.last_duration_seconds, 2),
        }


def _md5(text: str) -> str:
    return hashlib.md5(text.encode("utf-8"), usedforsecurity=False).hexdigest()


class BackchannelServer:
    def __init__(self, passwords: Callable[[], dict[str, str]], port: int = 0) -> None:
        # Đọc lại mỗi lần: mật khẩu đổi được qua SetUser
        self._passwords = passwords
        self.port = port
        self.stats = TalkStats()
        self._server: asyncio.Server | None = None
        self._nonces: set[str] = set()

    async def start(self, host: str = "0.0.0.0") -> None:  # noqa: S104 - camera giả lập nghe mọi card mạng
        self._server = await asyncio.start_server(self._client, host, self.port)
        self.port = self._server.sockets[0].getsockname()[1]
        log.info("RTSP backchannel giả lập nghe cổng %d", self.port)

    async def stop(self) -> None:
        if self._server is not None:
            self._server.close()
            await self._server.wait_closed()

    # ---- Xử lý kết nối ------------------------------------------------------------------------------

    async def _client(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        session_id: str | None = None
        playing = False
        try:
            while True:
                first = await reader.read(1)
                if not first:
                    return
                if first == b"$":
                    header = await reader.readexactly(3)
                    payload = await reader.readexactly(int.from_bytes(header[1:3], "big"))
                    if playing and header[0] == 0 and len(payload) >= 12:
                        self.stats.packets += 1
                        self.stats.bytes += len(payload) - 12
                        self.stats.payload_type = payload[1] & 0x7F
                    continue
                head = first + await reader.readuntil(b"\r\n\r\n")
                method, uri, headers = _parse_request(head.decode("utf-8", "replace"))
                length = int(headers.get("content-length", "0") or 0)
                if length:
                    await reader.readexactly(length)
                cseq = headers.get("cseq", "0")
                if method != "OPTIONS" and not self._authorized(method, uri, headers.get("authorization")):
                    nonce = secrets.token_hex(16)
                    self._nonces.add(nonce)
                    _reply(writer, 401, cseq, {"WWW-Authenticate": f'Digest realm="{REALM}", nonce="{nonce}"'})
                elif method == "OPTIONS":
                    _reply(writer, 200, cseq, {"Public": "OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN"})
                elif method == "DESCRIBE":
                    if REQUIRE_BACKCHANNEL not in headers.get("require", ""):
                        _reply(writer, 551, cseq, {"Unsupported": REQUIRE_BACKCHANNEL})
                    else:
                        _reply(writer, 200, cseq, {"Content-Type": "application/sdp", "Content-Base": uri + "/"}, SDP)
                elif method == "SETUP":
                    transport = headers.get("transport", "")
                    if "RTP/AVP/TCP" not in transport:
                        _reply(writer, 461, cseq)
                    else:
                        session_id = session_id or secrets.token_hex(8)
                        _reply(
                            writer,
                            200,
                            cseq,
                            {
                                "Session": f"{session_id};timeout=60",
                                "Transport": "RTP/AVP/TCP;unicast;interleaved=0-1",
                            },
                        )
                elif method == "PLAY":
                    playing = True
                    self._begin()
                    _reply(writer, 200, cseq, {"Session": session_id or ""})
                elif method == "TEARDOWN":
                    _reply(writer, 200, cseq, {"Session": session_id or ""})
                    await writer.drain()
                    return
                else:
                    _reply(writer, 405, cseq)
                await writer.drain()
        except (asyncio.IncompleteReadError, ConnectionError, asyncio.LimitOverrunError):
            pass
        finally:
            if playing:
                self._end()
            with contextlib.suppress(Exception):
                writer.close()

    def _begin(self) -> None:
        self.stats.sessions += 1
        self.stats.active = True
        self.stats.started_at = time.monotonic()

    def _end(self) -> None:
        duration = time.monotonic() - (self.stats.started_at or time.monotonic())
        self.stats.active = False
        self.stats.last_duration_seconds = duration

    def _authorized(self, method: str, uri: str, header: str | None) -> bool:
        if not header or not header.lower().startswith("digest "):
            return False
        params = {
            m.group(1).lower(): m.group(2) if m.group(2) is not None else m.group(3)
            for m in _AUTH_PARAM.finditer(header[7:])
        }
        user = params.get("username", "")
        password = self._passwords().get(user)
        if password is None or params.get("nonce") not in self._nonces:
            return False
        ha1 = _md5(f"{user}:{REALM}:{password}")
        ha2 = _md5(f"{method}:{params.get('uri', uri)}")
        return secrets.compare_digest(params.get("response", ""), _md5(f"{ha1}:{params['nonce']}:{ha2}"))


def _parse_request(text: str) -> tuple[str, str, dict[str, str]]:
    lines = text.split("\r\n")
    parts = lines[0].split(" ")
    method, uri = (parts[0], parts[1]) if len(parts) >= 2 else ("", "")
    headers: dict[str, str] = {}
    for line in lines[1:]:
        if ":" in line:
            k, v = line.split(":", 1)
            headers[k.strip().lower()] = v.strip()
    return method, uri, headers


_REASONS = {
    200: "OK",
    401: "Unauthorized",
    405: "Method Not Allowed",
    461: "Unsupported Transport",
    551: "Option not supported",
}


def _reply(
    writer: asyncio.StreamWriter, code: int, cseq: str, headers: dict[str, str] | None = None, body: str = ""
) -> None:
    lines = [f"RTSP/1.0 {code} {_REASONS.get(code, '')}", f"CSeq: {cseq}"]
    lines += [f"{k}: {v}" for k, v in (headers or {}).items()]
    data = body.encode("utf-8")
    if data:
        lines.append(f"Content-Length: {len(data)}")
    writer.write(("\r\n".join(lines) + "\r\n\r\n").encode("utf-8") + data)
