"""Đẩy sự kiện thiết bị vào RabbitMQ exchange ``device.events`` (SADD 3.7.3).

``publish`` chỉ đưa thông điệp vào hàng đợi trong bộ nhớ rồi trả về ngay; một tác vụ nền giữ kết nối,
tự kết nối lại khi RabbitMQ mất, và gửi lại thông điệp đang dở. Khi hàng đợi đầy thì bỏ thông điệp cũ nhất.
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import logging
from collections import deque
from datetime import UTC, datetime
from typing import Any, Protocol

import aio_pika
from aio_pika.abc import AbstractChannel, AbstractConnection, AbstractExchange

from app.metrics import EVENTS_DROPPED, EVENTS_PUBLISHED

log = logging.getLogger(__name__)

_TYPES = {
    "motion.detected": "MotionDetected",
    "device.online": "DeviceOnline",
    "device.offline": "DeviceOffline",
}


def message_type(routing_key: str) -> str:
    return _TYPES.get(routing_key, routing_key)


class EventSink(Protocol):
    def publish(self, routing_key: str, payload: dict[str, Any]) -> None: ...


class MemorySink:
    """Bộ nhận trong bộ nhớ, dùng cho test và khi chạy không có RabbitMQ."""

    def __init__(self) -> None:
        self.messages: list[tuple[str, dict[str, Any]]] = []
        self._changed = asyncio.Event()

    def publish(self, routing_key: str, payload: dict[str, Any]) -> None:
        self.messages.append((routing_key, payload))
        self._changed.set()

    async def wait_for(self, routing_key: str, count: int = 1, timeout: float = 5.0) -> list[dict[str, Any]]:
        async def matched() -> list[dict[str, Any]]:
            while True:
                found = [p for k, p in self.messages if k == routing_key]
                if len(found) >= count:
                    return found
                self._changed.clear()
                await self._changed.wait()

        return await asyncio.wait_for(matched(), timeout)


class RabbitPublisher:
    def __init__(self, url: str, exchange: str, *, max_pending: int = 10_000, max_backoff: float = 30.0) -> None:
        self._url = url
        self._exchange_name = exchange
        self._pending: deque[tuple[str, dict[str, Any]]] = deque(maxlen=max_pending)
        self._wakeup = asyncio.Event()
        self._max_backoff = max_backoff
        self._task: asyncio.Task[None] | None = None
        self._connection: AbstractConnection | None = None
        self.connected = False

    def start(self) -> None:
        if self._task is None:
            self._task = asyncio.create_task(self._run(), name="rabbitmq-publisher")

    async def stop(self) -> None:
        if self._task is not None:
            self._task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self._task
            self._task = None
        await self._close()

    def publish(self, routing_key: str, payload: dict[str, Any]) -> None:
        if len(self._pending) == self._pending.maxlen:
            EVENTS_DROPPED.inc()
            log.warning("Hàng đợi RabbitMQ đầy, bỏ thông điệp cũ nhất")
        self._pending.append((routing_key, payload))
        self._wakeup.set()

    @property
    def pending(self) -> int:
        return len(self._pending)

    async def _close(self) -> None:
        self.connected = False
        if self._connection is not None:
            with contextlib.suppress(Exception):
                await self._connection.close()
            self._connection = None

    async def _connect(self) -> AbstractExchange:
        self._connection = await aio_pika.connect(self._url, timeout=10)
        channel: AbstractChannel = await self._connection.channel(publisher_confirms=True)
        exchange = await channel.declare_exchange(self._exchange_name, aio_pika.ExchangeType.TOPIC, durable=True)
        self.connected = True
        log.info("Đã kết nối RabbitMQ, exchange %s", self._exchange_name)
        return exchange

    async def _run(self) -> None:
        backoff = 1.0
        while True:
            try:
                exchange = await self._connect()
                backoff = 1.0
                while True:
                    while not self._pending:
                        self._wakeup.clear()
                        await self._wakeup.wait()
                    routing_key, payload = self._pending[0]
                    await exchange.publish(_to_message(routing_key, payload), routing_key=routing_key)
                    self._pending.popleft()
                    EVENTS_PUBLISHED.labels(routing_key=routing_key).inc()
            except asyncio.CancelledError:
                raise
            except Exception as e:  # noqa: BLE001 - mọi lỗi kết nối đều phải thử lại, không làm sập Gateway
                log.warning(
                    "RabbitMQ không sẵn sàng (%s: %s), thử lại sau %.0f giây; %d thông điệp đang chờ",
                    type(e).__name__,
                    e,
                    backoff,
                    len(self._pending),
                )
                await self._close()
                await asyncio.sleep(backoff)
                backoff = min(backoff * 2, self._max_backoff)


def _to_message(routing_key: str, payload: dict[str, Any]) -> aio_pika.Message:
    return aio_pika.Message(
        body=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        content_type="application/json",
        content_encoding="utf-8",
        delivery_mode=aio_pika.DeliveryMode.PERSISTENT,
        message_id=str(payload.get("messageId", "")) or None,
        type=message_type(routing_key),
        timestamp=datetime.now(UTC),
        app_id="camhub-device-gateway",
    )
