"""Phiên đàm thoại (US-15): Core mở phiên qua REST nội bộ, trình duyệt gửi âm thanh qua WebSocket ``/talk/ws/{id}``.

- Mã phiên ngẫu nhiên 192 bit là thông tin xác thực duy nhất của WebSocket: dùng một lần, hết hạn nếu không kết
  nối trong ``ready_timeout`` giây, phiên dài nhất ``max_seconds`` giây.
- Mỗi camera chỉ một người nói cùng lúc (loa camera bán song công; nhiều camera chỉ nhận một backchannel).
- Kênh RTSP tới camera mở ngay khi tạo phiên để nhấn giữ là nói được, không chờ bắt tay.
- Phiên đã kết thúc được giữ ``retain_seconds`` giây để Core đọc thời lượng ghi nhật ký TALK_AUDIO.
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
import secrets
import time
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Literal

from app.onvif.client import OnvifClient
from app.onvif.errors import DeviceError, ErrorCode
from app.talk.rtsp import SAMPLE_RATE, Opener, RtspBackchannel

log = logging.getLogger(__name__)

TalkState = Literal["READY", "ACTIVE", "ENDED", "FAILED"]


@dataclass
class TalkSession:
    id: str
    device_key: str
    channel: RtspBackchannel
    created: float = field(default_factory=time.monotonic)
    state: TalkState = "READY"
    started_at: datetime | None = None
    ended_at: datetime | None = None
    ended_mono: float | None = None
    error: str | None = None

    @property
    def duration_seconds(self) -> float:
        if self.started_at is None:
            return 0.0
        end = self.ended_at or datetime.now(UTC)
        return max(0.0, (end - self.started_at).total_seconds())

    @property
    def audio_seconds(self) -> float:
        return self.channel.samples_sent / SAMPLE_RATE

    @property
    def open(self) -> bool:
        return self.state in ("READY", "ACTIVE")


async def backchannel_uri(client: OnvifClient) -> str:
    """URI RTSP của profile có đầu ra âm thanh (loa); camera không có loa thì báo NOT_SUPPORTED."""
    await client.connect()
    profiles = [p for p in await client.get_profiles() if p.has_audio_out]
    if not profiles:
        raise DeviceError(ErrorCode.NOT_SUPPORTED, "Camera không có loa (không có cấu hình đầu ra âm thanh)")
    # Ưu tiên profile có video: đa số camera gắn loa vào profile luồng chính
    profiles.sort(key=lambda p: p.encoder is None)
    uri = await client.get_stream_uri(profiles[0].token)
    if not uri:
        raise DeviceError(ErrorCode.NOT_SUPPORTED, "Camera không trả URI RTSP cho kênh âm thanh")
    return uri


class TalkManager:
    def __init__(
        self,
        *,
        ready_timeout: float = 30.0,
        max_seconds: float = 300.0,
        retain_seconds: float = 600.0,
        rtsp_timeout: float = 10.0,
    ) -> None:
        self._sessions: dict[str, TalkSession] = {}
        self._ready_timeout = ready_timeout
        self._max_seconds = max_seconds
        self._retain_seconds = retain_seconds
        self._rtsp_timeout = rtsp_timeout
        self._opening: set[str] = set()
        self._reaper: asyncio.Task[None] | None = None

    def start(self) -> None:
        self._reaper = asyncio.create_task(self._reap_loop())

    async def create(
        self, device_key: str, client: OnvifClient, opener: Opener, username: str, password: str
    ) -> TalkSession:
        # Giữ chỗ camera trong lúc bắt tay RTSP (không khóa toàn cục: camera chậm không chặn camera khác)
        if device_key in self._opening or any(s.device_key == device_key and s.open for s in self._sessions.values()):
            raise DeviceError(ErrorCode.BUSY, "Đang có người đàm thoại qua camera này")
        self._opening.add(device_key)
        try:
            uri = await backchannel_uri(client)
            channel = RtspBackchannel(uri, username, password, opener, self._rtsp_timeout)
            await channel.open()
        finally:
            self._opening.discard(device_key)
        session = TalkSession(secrets.token_urlsafe(24), device_key, channel)
        self._sessions[session.id] = session
        log.info("Mở phiên đàm thoại %s… tới %s (%s)", session.id[:6], device_key, channel.codec)
        return session

    def get(self, session_id: str) -> TalkSession | None:
        return self._sessions.get(session_id)

    def attach(self, session_id: str) -> TalkSession | None:
        """WebSocket nhận phiên: chỉ một lần, khi phiên còn READY."""
        session = self._sessions.get(session_id)
        if session is None or session.state != "READY":
            return None
        session.state = "ACTIVE"
        session.started_at = datetime.now(UTC)
        return session

    async def end(self, session_id: str, error: str | None = None) -> TalkSession | None:
        session = self._sessions.get(session_id)
        if session is None:
            return None
        if session.open:
            session.state = "FAILED" if error else "ENDED"
            session.error = error
            session.ended_at = datetime.now(UTC) if session.started_at else None
            session.ended_mono = time.monotonic()
            await session.channel.close()
            log.info(
                "Kết thúc phiên đàm thoại %s…: %.1f giây, %.1f giây âm thanh",
                session_id[:6],
                session.duration_seconds,
                session.audio_seconds,
            )
        return session

    async def _reap_loop(self) -> None:
        while True:
            await asyncio.sleep(1)
            await self.reap()

    async def reap(self) -> None:
        now = time.monotonic()
        for session in list(self._sessions.values()):
            expired = session.state == "READY" and now - session.created > self._ready_timeout
            too_long = session.state == "ACTIVE" and session.duration_seconds > self._max_seconds
            if expired or too_long:
                await self.end(session.id)
            elif session.open and session.channel.closed.is_set():
                await self.end(session.id, "Camera đóng kênh âm thanh")
            elif not session.open and session.ended_mono and now - session.ended_mono > self._retain_seconds:
                self._sessions.pop(session.id, None)

    async def close(self) -> None:
        if self._reaper is not None:
            self._reaper.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self._reaper
        for session_id in list(self._sessions):
            await self.end(session_id)
