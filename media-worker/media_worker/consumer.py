"""Nhận công việc từ RabbitMQ (SADD 3.7.3).

- Xuất clip: hàng đợi bền ``media-worker.export`` gắn vào exchange ``jobs`` với khóa ``export.requested``, prefetch 2;
  kết quả ``export.progress``/``export.completed``/``export.failed`` gửi về ``job.results``.
- Chụp ảnh sự kiện (US-36.3): hàng đợi bền ``media-worker.snapshot`` với khóa ``snapshot.requested``, prefetch 4;
  kết quả ``snapshot.created``/``snapshot.failed`` gửi về ``job.results``.

Mỗi hàng đợi dùng kênh riêng (prefetch riêng), xác nhận thủ công.
Kết nối dùng ``aio_pika.connect_robust`` nên tự nối lại và khôi phục hàng đợi/consumer khi RabbitMQ khởi động lại;
lần kết nối đầu tiên thất bại thì thử lại với thời gian chờ tăng dần, không làm sập worker.
"""

from __future__ import annotations

import asyncio
import contextlib
import enum
import json
import logging
import time
import uuid
from collections import OrderedDict
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any, Protocol

import aio_pika
from aio_pika.abc import AbstractExchange, AbstractIncomingMessage, AbstractRobustConnection
from pydantic import ValidationError

from media_worker.export_job import (
    ExportError,
    ExportRequest,
    ExportResult,
    completed_payload,
    failed_payload,
    loose_ids,
    parse_request,
    progress_payload,
)
from media_worker.metrics import EXPORT_DURATION, EXPORT_JOBS, SNAPSHOT_DURATION, SNAPSHOT_JOBS
from media_worker.settings import Settings
from media_worker.snapshot_job import SnapshotError, SnapshotRequest, SnapshotResult
from media_worker.snapshot_job import created_payload as snapshot_created_payload
from media_worker.snapshot_job import failed_payload as snapshot_failed_payload
from media_worker.snapshot_job import loose_ids as snapshot_loose_ids
from media_worker.snapshot_job import parse_request as parse_snapshot_request

log = logging.getLogger(__name__)

Publish = Callable[[str, dict[str, Any]], Awaitable[None]]
Handler = Callable[[ExportRequest], Awaitable[ExportResult]]
SnapshotHandler = Callable[[SnapshotRequest], Awaitable[SnapshotResult]]

_TYPES = {
    "export.progress": "ExportProgress",
    "export.completed": "ExportCompleted",
    "export.failed": "ExportFailed",
    "snapshot.created": "SnapshotCreated",
    "snapshot.failed": "SnapshotFailed",
}


class Outcome(enum.Enum):
    ACK = "ack"
    REJECT = "reject"  # gửi sang dead-letter, không xử lý lại


class Processor(Protocol):
    async def process(self, body: bytes, message_id: str | None, publish: Publish) -> Outcome: ...


class _Dedup:
    """Chống xử lý lặp theo khóa thông điệp bằng LRU trong bộ nhớ (đã có kết quả hoặc đang chạy)."""

    def __init__(self, seen_capacity: int) -> None:
        self._seen: OrderedDict[str, None] = OrderedDict()
        self._capacity = seen_capacity
        self._in_flight: set[str] = set()

    def _remember(self, key: str) -> None:
        self._seen[key] = None
        self._seen.move_to_end(key)
        while len(self._seen) > self._capacity:
            self._seen.popitem(last=False)

    def _is_duplicate(self, key: str) -> bool:
        return key in self._seen or key in self._in_flight


class ExportProcessor(_Dedup):
    """Xử lý một thông điệp ``export.requested``, độc lập với RabbitMQ để dễ kiểm thử.

    Chống xử lý lặp theo ``message_id`` (hoặc ``exportId`` nếu thiếu): thông điệp đã có kết quả
    hoặc đang chạy thì chỉ xác nhận rồi bỏ qua.
    """

    def __init__(self, handler: Handler, seen_capacity: int = 2048) -> None:
        super().__init__(seen_capacity)
        self._handler = handler

    async def process(self, body: bytes, message_id: str | None, publish: Publish) -> Outcome:
        try:
            req = parse_request(body)
        except (ValidationError, ValueError) as e:
            ids = loose_ids(body)
            EXPORT_JOBS.labels(result="invalid").inc()
            log.warning("Payload export.requested sai hợp đồng: %s", str(e).replace("\n", "; ")[:300])
            if ids is None:
                return Outcome.REJECT
            await publish("export.failed", failed_payload(ids[0], ids[1], "INTERNAL", "Yêu cầu xuất clip không hợp lệ"))
            return Outcome.ACK

        key = message_id or f"export:{req.export_id}"
        if self._is_duplicate(key):
            EXPORT_JOBS.labels(result="duplicate").inc()
            log.info("Bỏ qua thông điệp lặp %s (export %s)", key, req.export_id)
            return Outcome.ACK

        self._in_flight.add(key)
        try:
            started = time.monotonic()
            await publish("export.progress", progress_payload(req))
            try:
                result = await self._handler(req)
            except ExportError as e:
                log.warning("Xuất %s thất bại [%s]: %s", req.export_id, e.code, e.message)
                await publish(
                    "export.failed", failed_payload(str(req.export_id), str(req.organization_id), e.code, e.message)
                )
                EXPORT_JOBS.labels(result="failed").inc()
            except Exception:  # noqa: BLE001 - lỗi không lường trước vẫn phải báo Core, không để công việc treo
                log.exception("Lỗi không lường trước khi xuất %s", req.export_id)
                await publish(
                    "export.failed",
                    failed_payload(str(req.export_id), str(req.organization_id), "INTERNAL", "Lỗi nội bộ Media Worker"),
                )
                EXPORT_JOBS.labels(result="failed").inc()
            else:
                await publish("export.completed", completed_payload(req, result))
                EXPORT_JOBS.labels(result="completed").inc()
                log.info(
                    "Đã xuất %s → %s/%s (%d byte)", req.export_id, result.bucket, result.object_key, result.size_bytes
                )
            EXPORT_DURATION.observe(time.monotonic() - started)
            self._remember(key)
            return Outcome.ACK
        finally:
            self._in_flight.discard(key)


class SnapshotProcessor(_Dedup):
    """Xử lý một thông điệp ``snapshot.requested`` (US-36.3), độc lập với RabbitMQ để dễ kiểm thử.

    Chống xử lý lặp theo ``message_id`` (hoặc ``snapshotId`` nếu thiếu) như xuất clip. Không gửi tiến độ:
    chụp ảnh chỉ mất vài giây nên Core chỉ cần ``snapshot.created`` hoặc ``snapshot.failed``.
    """

    def __init__(self, handler: SnapshotHandler, seen_capacity: int = 4096) -> None:
        super().__init__(seen_capacity)
        self._handler = handler

    async def process(self, body: bytes, message_id: str | None, publish: Publish) -> Outcome:
        try:
            req = parse_snapshot_request(body)
        except (ValidationError, ValueError) as e:
            loose = snapshot_loose_ids(body)
            SNAPSHOT_JOBS.labels(result="invalid").inc()
            log.warning("Payload snapshot.requested sai hợp đồng: %s", str(e).replace("\n", "; ")[:300])
            if loose is None:
                return Outcome.REJECT
            await publish(
                "snapshot.failed", snapshot_failed_payload(*loose, "INTERNAL", "Yêu cầu chụp ảnh không hợp lệ")
            )
            return Outcome.ACK

        key = message_id or f"snapshot:{req.snapshot_id}"
        if self._is_duplicate(key):
            SNAPSHOT_JOBS.labels(result="duplicate").inc()
            log.info("Bỏ qua thông điệp lặp %s (ảnh %s)", key, req.snapshot_id)
            return Outcome.ACK

        ids = (str(req.snapshot_id), str(req.organization_id), str(req.camera_id))
        self._in_flight.add(key)
        try:
            started = time.monotonic()
            try:
                result = await self._handler(req)
            except SnapshotError as e:
                log.warning("Chụp ảnh %s thất bại [%s]: %s", req.snapshot_id, e.code, e.message)
                await publish("snapshot.failed", snapshot_failed_payload(*ids, e.code, e.message))
                SNAPSHOT_JOBS.labels(result="failed").inc()
            except Exception:  # noqa: BLE001 - lỗi không lường trước vẫn phải báo Core, không để công việc treo
                log.exception("Lỗi không lường trước khi chụp ảnh %s", req.snapshot_id)
                await publish("snapshot.failed", snapshot_failed_payload(*ids, "INTERNAL", "Lỗi nội bộ Media Worker"))
                SNAPSHOT_JOBS.labels(result="failed").inc()
            else:
                await publish("snapshot.created", snapshot_created_payload(req, result))
                SNAPSHOT_JOBS.labels(result="created").inc()
                log.info(
                    "Đã chụp ảnh %s → %s/%s (%d byte)",
                    req.snapshot_id,
                    result.bucket,
                    result.object_key,
                    result.size_bytes,
                )
            SNAPSHOT_DURATION.observe(time.monotonic() - started)
            self._remember(key)
            return Outcome.ACK
        finally:
            self._in_flight.discard(key)


def to_message(routing_key: str, payload: dict[str, Any]) -> aio_pika.Message:
    return aio_pika.Message(
        body=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        content_type="application/json",
        content_encoding="utf-8",
        delivery_mode=aio_pika.DeliveryMode.PERSISTENT,
        message_id=str(uuid.uuid4()),
        type=_TYPES.get(routing_key, routing_key),
        timestamp=datetime.now(UTC),
        app_id="camhub-media-worker",
    )


@dataclass(frozen=True)
class Subscription:
    """Một hàng đợi bền gắn vào exchange ``jobs`` cùng bộ xử lý của nó."""

    queue: str
    routing_key: str
    prefetch: int
    processor: Processor


class JobConsumer:
    def __init__(self, settings: Settings, subscriptions: list[Subscription]) -> None:
        self._settings = settings
        self._subscriptions = subscriptions
        self._task: asyncio.Task[None] | None = None
        self._connection: AbstractRobustConnection | None = None
        self._results: AbstractExchange | None = None
        self._ready = False

    @property
    def connected(self) -> bool:
        conn = self._connection
        return self._ready and conn is not None and not conn.is_closed and conn.connected.is_set()

    def start(self) -> None:
        if self._task is None:
            self._task = asyncio.create_task(self._run(), name="job-consumer")

    async def stop(self) -> None:
        if self._task is not None:
            self._task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self._task
            self._task = None
        self._ready = False
        if self._connection is not None:
            with contextlib.suppress(Exception):
                await self._connection.close()
            self._connection = None

    async def _setup(self) -> None:
        s = self._settings
        self._connection = await aio_pika.connect_robust(s.resolved_rabbitmq_url(), timeout=10)
        publish_channel = await self._connection.channel(publisher_confirms=True)
        self._results = await publish_channel.declare_exchange(
            s.results_exchange, aio_pika.ExchangeType.TOPIC, durable=True
        )
        for sub in self._subscriptions:
            # Kênh riêng cho từng hàng đợi để prefetch độc lập (ảnh nhanh không bị kẹt sau clip dài)
            channel = await self._connection.channel()
            await channel.set_qos(prefetch_count=sub.prefetch)
            jobs = await channel.declare_exchange(s.jobs_exchange, aio_pika.ExchangeType.TOPIC, durable=True)
            queue = await channel.declare_queue(sub.queue, durable=True)
            await queue.bind(jobs, routing_key=sub.routing_key)
            await queue.consume(self._handler_for(sub), no_ack=False)
            log.info(
                "Đã kết nối RabbitMQ, nhận %s từ hàng đợi %s (prefetch %d)", sub.routing_key, sub.queue, sub.prefetch
            )
        self._ready = True

    async def _run(self) -> None:
        backoff = 1.0
        while True:
            try:
                await self._setup()
                break
            except asyncio.CancelledError:
                raise
            except Exception as e:  # noqa: BLE001 - RabbitMQ chưa sẵn sàng hoặc thiếu secret: thử lại, không sập worker
                log.warning("RabbitMQ không sẵn sàng (%s: %s), thử lại sau %.0f giây", type(e).__name__, e, backoff)
                if self._connection is not None:
                    with contextlib.suppress(Exception):
                        await self._connection.close()
                    self._connection = None
                await asyncio.sleep(backoff)
                backoff = min(backoff * 2, self._settings.connect_backoff_max_seconds)
        # connect_robust tự kết nối lại từ đây; tác vụ chỉ chờ tới khi bị hủy
        await asyncio.Event().wait()

    async def _publish(self, routing_key: str, payload: dict[str, Any]) -> None:
        if self._results is None:
            raise RuntimeError("Chưa kết nối RabbitMQ")
        await self._results.publish(to_message(routing_key, payload), routing_key=routing_key)

    def _handler_for(self, sub: Subscription) -> Callable[[AbstractIncomingMessage], Awaitable[None]]:
        async def on_message(message: AbstractIncomingMessage) -> None:
            try:
                outcome = await sub.processor.process(message.body, message.message_id, self._publish)
            except Exception:  # noqa: BLE001 - thường do mất kết nối khi gửi kết quả: trả lại hàng đợi để xử lý lại
                log.exception("Không gửi được kết quả (%s), trả thông điệp về hàng đợi", sub.routing_key)
                with contextlib.suppress(Exception):
                    await message.nack(requeue=True)
                return
            with contextlib.suppress(Exception):
                if outcome is Outcome.ACK:
                    await message.ack()
                else:
                    await message.reject(requeue=False)

        return on_message
