"""Client RTSP backchannel âm thanh theo ONVIF Streaming Spec mục 5.3 (US-15).

DESCRIBE kèm ``Require: www.onvif.org/ver20/backchannel``; track âm thanh ``a=sendonly`` trong SDP là đường gửi
xuống loa camera. Chỉ dùng RTP xen kẽ trên cùng kết nối TCP (đi được qua tunnel SOCKS5 của site) và G.711
(PCMU/PCMA 8 kHz) – codec mọi camera ONVIF có loa đều hỗ trợ.
"""

from __future__ import annotations

import asyncio
import base64
import contextlib
import hashlib
import logging
import re
import secrets
import struct
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from urllib.parse import urlsplit, urlunsplit

from app.onvif.errors import DeviceError, ErrorCode
from app.talk import g711

log = logging.getLogger(__name__)

REQUIRE = "www.onvif.org/ver20/backchannel"
SAMPLE_RATE = 8000
# 20 ms mỗi gói RTP
SAMPLES_PER_PACKET = 160
_STATIC_PT = {0: "PCMU", 8: "PCMA"}
_AUTH_PARAM = re.compile(r'(\w+)=(?:"([^"]*)"|([^,\s]*))')

Opener = Callable[[str, int], Awaitable[tuple[asyncio.StreamReader, asyncio.StreamWriter]]]


@dataclass(frozen=True)
class Response:
    status: int
    headers: dict[str, str]
    body: str


@dataclass(frozen=True)
class Track:
    control: str
    payload_type: int
    codec: str


def parse_backchannel(sdp: str, base: str) -> Track | None:
    """Track âm thanh ``sendonly`` đầu tiên có G.711; ``None`` nếu camera không có backchannel dùng được."""
    for section in re.split(r"\r?\n(?=m=)", sdp):
        lines = [line.strip() for line in section.splitlines()]
        if not lines or not lines[0].startswith("m=audio") or "a=sendonly" not in lines:
            continue
        payloads = [int(p) for p in lines[0].split()[3:] if p.isdigit()]
        names = {}
        control = ""
        for line in lines:
            if line.startswith("a=rtpmap:"):
                pt, _, enc = line[9:].partition(" ")
                name, _, rate = enc.partition("/")
                if pt.isdigit() and rate.split("/")[0] == str(SAMPLE_RATE):
                    names[int(pt)] = name.upper()
            elif line.startswith("a=control:"):
                control = line[10:]
        for pt in payloads:
            codec = names.get(pt) or _STATIC_PT.get(pt)
            if codec in ("PCMU", "PCMA"):
                return Track(_resolve(base, control), pt, codec)
    return None


def _resolve(base: str, control: str) -> str:
    if not control or control == "*":
        return base
    if control.lower().startswith("rtsp://"):
        return control
    return base.rstrip("/") + "/" + control.lstrip("/")


def strip_credentials(uri: str) -> tuple[str, str | None, str | None]:
    """URI không kèm ``user:pass@`` (một số camera trả kèm trong GetStreamUri) và tài khoản tách ra."""
    parts = urlsplit(uri)
    if parts.username is None:
        return uri, None, None
    netloc = parts.hostname or ""
    if parts.port:
        netloc += f":{parts.port}"
    return urlunsplit(parts._replace(netloc=netloc)), parts.username, parts.password


def _md5(text: str) -> str:
    return hashlib.md5(text.encode("utf-8"), usedforsecurity=False).hexdigest()


class _Auth:
    """Digest (RFC 2617, MD5, có/không qop) hoặc Basic, theo thử thách 401 của camera."""

    def __init__(self, username: str, password: str) -> None:
        self.username = username
        self.password = password
        self.challenge: dict[str, str] | None = None
        self.basic = False
        self.nc = 0

    def update(self, header: str) -> bool:
        scheme, _, rest = header.partition(" ")
        if scheme.lower() == "digest":
            self.challenge = {
                m.group(1).lower(): m.group(2) if m.group(2) is not None else m.group(3)
                for m in _AUTH_PARAM.finditer(rest)
            }
            self.nc = 0
            return True
        if scheme.lower() == "basic":
            self.basic = True
            return True
        return False

    def header(self, method: str, uri: str) -> str | None:
        if self.challenge is not None:
            c = self.challenge
            realm, nonce = c.get("realm", ""), c.get("nonce", "")
            ha1 = _md5(f"{self.username}:{realm}:{self.password}")
            ha2 = _md5(f"{method}:{uri}")
            fields = f'username="{self.username}", realm="{realm}", nonce="{nonce}", uri="{uri}"'
            if "auth" in [q.strip() for q in c.get("qop", "").split(",")]:
                self.nc += 1
                nc, cnonce = f"{self.nc:08x}", secrets.token_hex(8)
                response = _md5(f"{ha1}:{nonce}:{nc}:{cnonce}:auth:{ha2}")
                fields += f', qop=auth, nc={nc}, cnonce="{cnonce}"'
            else:
                response = _md5(f"{ha1}:{nonce}:{ha2}")
            if "opaque" in c:
                fields += f', opaque="{c["opaque"]}"'
            return f'Digest {fields}, response="{response}"'
        if self.basic:
            token = base64.b64encode(f"{self.username}:{self.password}".encode()).decode()
            return f"Basic {token}"
        return None


class RtspBackchannel:
    """Một phiên backchannel: ``open()`` (DESCRIBE → SETUP → PLAY), ``send_pcm()`` nhiều lần, ``close()``."""

    def __init__(self, uri: str, username: str, password: str, opener: Opener, timeout: float = 10.0) -> None:
        self.uri, user, pwd = strip_credentials(uri)
        self._auth = _Auth(user or username, pwd if pwd is not None else password)
        self._opener = opener
        self._timeout = timeout
        self._reader: asyncio.StreamReader | None = None
        self._writer: asyncio.StreamWriter | None = None
        self._cseq = 0
        self._session: str | None = None
        self._keepalive_seconds = 25.0
        self._tasks: list[asyncio.Task[None]] = []
        self._buffer = bytearray()
        self._seq = secrets.randbelow(0x10000)
        self._timestamp = secrets.randbelow(0x100000000)
        self._ssrc = secrets.randbelow(0x100000000)
        self._first = True
        self.track: Track | None = None
        self.samples_sent = 0
        self.closed = asyncio.Event()

    @property
    def codec(self) -> str:
        return self.track.codec if self.track else ""

    async def open(self) -> None:
        parts = urlsplit(self.uri)
        if parts.scheme.lower() != "rtsp" or not parts.hostname:
            raise DeviceError(ErrorCode.NOT_SUPPORTED, "URI âm thanh của camera không phải RTSP")
        try:
            self._reader, self._writer = await asyncio.wait_for(
                self._opener(parts.hostname, parts.port or 554), self._timeout
            )
        except (OSError, TimeoutError) as e:
            raise DeviceError(ErrorCode.UNREACHABLE, f"Không kết nối được RTSP {parts.hostname}: {e}") from e
        try:
            await self._handshake()
        except BaseException:
            await self.close()
            raise

    async def _handshake(self) -> None:
        resp = await self._request("DESCRIBE", self.uri, {"Accept": "application/sdp", "Require": REQUIRE})
        if resp.status == 551 or (resp.status == 200 and not resp.body):
            raise DeviceError(ErrorCode.NOT_SUPPORTED, "Camera không hỗ trợ âm thanh hai chiều (ONVIF backchannel)")
        self._check(resp, "DESCRIBE")
        base = resp.headers.get("content-base") or resp.headers.get("content-location") or self.uri
        self.track = parse_backchannel(resp.body, base)
        if self.track is None:
            raise DeviceError(ErrorCode.NOT_SUPPORTED, "Camera không có kênh âm thanh G.711 để phát ra loa")
        resp = await self._request(
            "SETUP", self.track.control, {"Transport": "RTP/AVP/TCP;unicast;interleaved=0-1", "Require": REQUIRE}
        )
        self._check(resp, "SETUP")
        session, _, params = resp.headers.get("session", "").partition(";")
        self._session = session.strip()
        timeout = re.search(r"timeout=(\d+)", params)
        if timeout:
            self._keepalive_seconds = max(5.0, min(25.0, int(timeout.group(1)) / 2))
        resp = await self._request("PLAY", self.uri, {"Range": "npt=0.000-", "Require": REQUIRE})
        self._check(resp, "PLAY")
        self._tasks = [asyncio.create_task(self._drain()), asyncio.create_task(self._keepalive())]

    @staticmethod
    def _check(resp: Response, method: str) -> None:
        if resp.status == 401:
            raise DeviceError(ErrorCode.AUTH_FAILED, "Camera từ chối tài khoản khi mở kênh âm thanh")
        if resp.status != 200:
            raise DeviceError(ErrorCode.DEVICE_ERROR, f"Camera trả {resp.status} cho {method} kênh âm thanh")

    async def _request(self, method: str, uri: str, headers: dict[str, str]) -> Response:
        resp = await self._send(method, uri, headers)
        if resp.status == 401 and self._auth.update(resp.headers.get("www-authenticate", "")):
            resp = await self._send(method, uri, headers)
        return resp

    async def _send(self, method: str, uri: str, headers: dict[str, str]) -> Response:
        self._write_request(method, uri, headers)
        assert self._writer is not None
        try:
            await self._writer.drain()
            return await asyncio.wait_for(self._read_response(), self._timeout)
        except (OSError, TimeoutError, asyncio.IncompleteReadError) as e:
            raise DeviceError(ErrorCode.UNREACHABLE, f"Mất kết nối RTSP khi {method}: {e}") from e

    def _write_request(self, method: str, uri: str, headers: dict[str, str]) -> None:
        assert self._writer is not None
        self._cseq += 1
        lines = [f"{method} {uri} RTSP/1.0", f"CSeq: {self._cseq}", "User-Agent: CamHub-Gateway"]
        auth = self._auth.header(method, uri)
        if auth:
            lines.append(f"Authorization: {auth}")
        if self._session:
            lines.append(f"Session: {self._session}")
        lines += [f"{k}: {v}" for k, v in headers.items()]
        self._writer.write(("\r\n".join(lines) + "\r\n\r\n").encode("utf-8"))

    async def _read_response(self) -> Response:
        assert self._reader is not None
        while True:
            first = await self._reader.readexactly(1)
            if first == b"$":
                # RTCP/RTP xen kẽ từ camera trước khi có phản hồi: bỏ qua
                header = await self._reader.readexactly(3)
                await self._reader.readexactly(int.from_bytes(header[1:3], "big"))
                continue
            head = (first + await self._reader.readuntil(b"\r\n\r\n")).decode("utf-8", "replace")
            lines = head.split("\r\n")
            status_parts = lines[0].split(" ", 2)
            if len(status_parts) < 2 or not status_parts[0].startswith("RTSP/"):
                raise DeviceError(ErrorCode.DEVICE_ERROR, "Phản hồi RTSP không hợp lệ")
            headers: dict[str, str] = {}
            for line in lines[1:]:
                if ":" in line:
                    k, v = line.split(":", 1)
                    headers[k.strip().lower()] = v.strip()
            length = int(headers.get("content-length", "0") or 0)
            body = (await self._reader.readexactly(length)).decode("utf-8", "replace") if length else ""
            return Response(int(status_parts[1]), headers, body)

    async def _drain(self) -> None:
        """Sau PLAY: đọc bỏ phản hồi keepalive và RTCP; camera đóng kết nối thì phiên kết thúc."""
        try:
            while True:
                await self._read_response()
        except (OSError, asyncio.IncompleteReadError, DeviceError, asyncio.LimitOverrunError):
            self.closed.set()

    async def _keepalive(self) -> None:
        while not self.closed.is_set():
            await asyncio.sleep(self._keepalive_seconds)
            with contextlib.suppress(OSError):
                self._write_request("OPTIONS", self.uri, {})

    async def send_pcm(self, pcm16le: bytes) -> None:
        """Nhận PCM 16 bit mono 8 kHz, gửi thành gói RTP 20 ms."""
        if self.closed.is_set() or self._writer is None or self.track is None:
            raise DeviceError(ErrorCode.UNREACHABLE, "Kênh âm thanh tới camera đã đóng")
        self._buffer += pcm16le
        size = SAMPLES_PER_PACKET * 2
        while len(self._buffer) >= size:
            chunk = bytes(self._buffer[:size])
            del self._buffer[:size]
            payload = g711.encode(chunk, self.track.codec)
            marker = 0x80 if self._first else 0
            self._first = False
            rtp = struct.pack("!BBHII", 0x80, marker | self.track.payload_type, self._seq, self._timestamp, self._ssrc)
            self._writer.write(b"$\x00" + struct.pack("!H", len(rtp) + len(payload)) + rtp + payload)
            self._seq = (self._seq + 1) & 0xFFFF
            self._timestamp = (self._timestamp + SAMPLES_PER_PACKET) & 0xFFFFFFFF
            self.samples_sent += SAMPLES_PER_PACKET
        try:
            await self._writer.drain()
        except OSError as e:
            self.closed.set()
            raise DeviceError(ErrorCode.UNREACHABLE, f"Mất kết nối RTSP: {e}") from e

    async def close(self) -> None:
        for task in self._tasks:
            task.cancel()
        writer = self._writer
        if writer is not None:
            if self._session and not self.closed.is_set():
                with contextlib.suppress(Exception):
                    self._write_request("TEARDOWN", self.uri, {})
                    await asyncio.wait_for(writer.drain(), 2)
            self._writer = None
            writer.close()
            with contextlib.suppress(Exception):
                await asyncio.wait_for(writer.wait_closed(), 2)
        self.closed.set()
