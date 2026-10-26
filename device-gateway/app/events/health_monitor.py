"""Theo dõi trạng thái online/offline của camera (US-04.1).

Core đăng ký toàn bộ danh sách camera (``PUT /v1/monitor/targets``); Gateway kiểm tra định kỳ:

- ONVIF: ``GetSystemDateAndTime`` không xác thực. Thiết bị trả lời SOAP (kể cả Fault) là online.
- RTSP: mở TCP tới ``rtspHost:rtspPort`` (mặc định 554), gửi ``OPTIONS``; mọi phản hồi ``RTSP/1.0`` là online.

Target có ``proxy`` (camera ở site xa, TS-06.3) thì cả hai cách kiểm tra đều đi qua tunnel SOCKS5. Đầu ghi
(NVR/DVR) được theo dõi như một target ONVIF duy nhất.

Chỉ coi là OFFLINE sau ``failure_threshold`` lần thất bại liên tiếp để tránh nhấp nháy. Khi trạng thái đổi
thì đẩy ``device.online`` / ``device.offline`` (``source = MONITOR``). Sau mỗi lần đăng ký, lần kiểm tra
có kết luận đầu tiên luôn phát trạng thái hiện tại để Core đồng bộ.
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
import time
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Literal

from app.events.normalize import format_instant
from app.events.publisher import EventSink, message_type
from app.metrics import MONITOR_CAMERAS
from app.models import MonitorTarget, MonitorTargetStatus
from app.onvif.client import DeviceTarget, OnvifClient
from app.onvif.errors import DeviceError, ErrorCode
from app.transport.tunnel_router import SocksError, TunnelRouter

log = logging.getLogger(__name__)

Status = Literal["ONLINE", "OFFLINE", "UNKNOWN"]
DEFAULT_RTSP_PORT = 554


def _now() -> str:
    return format_instant(datetime.now(UTC))


@dataclass
class _Entry:
    target: MonitorTarget
    status: Status = "UNKNOWN"
    failures: int = 0
    announce: bool = True
    last_change_at: str | None = None
    last_check_at: str | None = None
    last_error: str | None = None

    def to_status(self) -> MonitorTargetStatus:
        return MonitorTargetStatus(
            **self.target.model_dump(),
            status=self.status,
            last_change_at=self.last_change_at,
            last_check_at=self.last_check_at,
            last_error=self.last_error,
        )


class HealthMonitor:
    def __init__(
        self,
        router: TunnelRouter,
        sink: EventSink,
        *,
        timeout_seconds: float = 5.0,
        concurrency: int = 50,
        failure_threshold: int = 2,
    ) -> None:
        self._router = router
        self._sink = sink
        self._timeout = timeout_seconds
        self._semaphore = asyncio.Semaphore(concurrency)
        self._threshold = failure_threshold
        self._entries: dict[str, _Entry] = {}
        self._interval = 30.0
        self._task: asyncio.Task[None] | None = None

    @property
    def interval_seconds(self) -> float:
        return self._interval

    def statuses(self) -> list[MonitorTargetStatus]:
        return [e.to_status() for e in self._entries.values()]

    async def set_targets(self, targets: list[MonitorTarget], interval_seconds: float) -> list[MonitorTargetStatus]:
        """Thay toàn bộ danh sách. Camera giữ nguyên địa chỉ thì giữ trạng thái, nhưng vẫn phát lại lần đầu."""
        entries: dict[str, _Entry] = {}
        for target in targets:
            old = self._entries.get(target.camera_id)
            if old is not None and old.target == target:
                old.announce = True
                entries[target.camera_id] = old
            else:
                entries[target.camera_id] = _Entry(target=target)
        self._entries = entries
        self._interval = interval_seconds
        self._update_gauge()
        await self._restart()
        log.info("Theo dõi %d camera, chu kỳ %.0f giây", len(entries), interval_seconds)
        return self.statuses()

    async def close(self) -> None:
        if self._task is not None:
            self._task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self._task
            self._task = None

    async def _restart(self) -> None:
        await self.close()
        if self._entries:
            self._task = asyncio.create_task(self._run(), name="health-monitor")

    async def _run(self) -> None:
        while True:
            started = time.monotonic()
            try:
                await self.check_all()
            except Exception:
                log.exception("Lỗi vòng kiểm tra trạng thái camera")
            await asyncio.sleep(max(0.0, self._interval - (time.monotonic() - started)))

    async def check_all(self) -> None:
        await asyncio.gather(*(self._check(entry) for entry in list(self._entries.values())))
        self._update_gauge()

    async def _check(self, entry: _Entry) -> None:
        async with self._semaphore:
            error = await self.probe(entry.target)
        entry.last_check_at = _now()
        if self._entries.get(entry.target.camera_id) is not entry:
            return  # đã bị thay bởi danh sách mới trong lúc kiểm tra
        previous = entry.status
        if error is None:
            entry.failures = 0
            entry.last_error = None
            new_status: Status = "ONLINE"
        else:
            entry.failures += 1
            entry.last_error = error
            new_status = "OFFLINE" if entry.failures >= self._threshold else previous
        if new_status == "UNKNOWN":
            return
        if new_status != previous:
            entry.status = new_status
            entry.last_change_at = entry.last_check_at
            log.info("Camera %s: %s → %s", entry.target.camera_id, previous, new_status)
        if new_status != previous or entry.announce:
            entry.announce = False
            self._emit(entry, new_status)

    def _emit(self, entry: _Entry, status: Status) -> None:
        routing_key = "device.online" if status == "ONLINE" else "device.offline"
        reason = f"{entry.target.protocol} trả lời" if status == "ONLINE" else (entry.last_error or "Không phản hồi")
        self._sink.publish(
            routing_key,
            {
                "messageId": str(uuid.uuid4()),
                "type": message_type(routing_key),
                "cameraId": entry.target.camera_id,
                "occurredAt": entry.last_change_at or _now(),
                "reason": reason,
                "source": "MONITOR",
            },
        )

    def _update_gauge(self) -> None:
        for status in ("ONLINE", "OFFLINE", "UNKNOWN"):
            MONITOR_CAMERAS.labels(status=status).set(sum(1 for e in self._entries.values() if e.status == status))

    # ---- kiểm tra từng giao thức ----------------------------------------------------------------------

    async def probe(self, target: MonitorTarget) -> str | None:
        """``None`` nếu thiết bị trả lời, ngược lại là mô tả lỗi."""
        if target.protocol == "RTSP":
            return await self._probe_rtsp(
                target.rtsp_host or target.host, target.rtsp_port or DEFAULT_RTSP_PORT, target.proxy
            )
        return await self._probe_onvif(target.host, target.port, target.proxy)

    async def _probe_onvif(self, host: str, port: int, proxy: str | None = None) -> str | None:
        client = OnvifClient(
            DeviceTarget(host, port, "", "", proxy),
            self._router.client(proxy),
            timeout=self._timeout,
            rewrite_xaddr=False,
        )
        try:
            await client.get_system_date_and_time(auth=False)
        except DeviceError as e:
            # Thiết bị trả SOAP Fault (kể cả đòi xác thực) vẫn là đang hoạt động
            if e.code in (ErrorCode.UNREACHABLE, ErrorCode.NOT_ONVIF):
                return f"{e.code}: {e.detail}"
        return None

    async def _probe_rtsp(self, host: str, port: int, proxy: str | None = None) -> str | None:
        netloc = f"[{host}]:{port}" if ":" in host else f"{host}:{port}"
        writer: asyncio.StreamWriter | None = None
        try:
            async with asyncio.timeout(self._timeout):
                reader, writer = await self._router.open_connection(host, port, proxy)
                writer.write(
                    f"OPTIONS rtsp://{netloc}/ RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: CamHub-Gateway\r\n\r\n".encode()
                )
                await writer.drain()
                line = await reader.readline()
        except SocksError as e:
            return f"{ErrorCode.UNREACHABLE}: không kết nối được RTSP {netloc} qua {proxy} ({e})"
        except TimeoutError:
            return f"{ErrorCode.UNREACHABLE}: RTSP {netloc} không phản hồi trong {self._timeout:g} giây"
        except OSError as e:
            return f"{ErrorCode.UNREACHABLE}: không kết nối được RTSP {netloc} ({e.strerror or type(e).__name__})"
        finally:
            if writer is not None:
                writer.close()
                with contextlib.suppress(OSError):
                    await writer.wait_closed()
        if not line.startswith(b"RTSP/1.0"):
            return f"{ErrorCode.NOT_SUPPORTED}: {netloc} không trả lời giao thức RTSP"
        return None
