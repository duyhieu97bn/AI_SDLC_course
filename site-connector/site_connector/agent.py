"""Vòng đời Site Connector: kích hoạt -> heartbeat 30 giây -> giữ đường hầm theo cấu hình -> làm việc được giao."""

from __future__ import annotations

import asyncio
import json
import logging
import socket
import time
from collections import OrderedDict
from typing import Any
from urllib.parse import urlsplit

from site_connector import __version__, discovery, state
from site_connector.api import (
    ActivationError,
    ApiError,
    CoreClient,
    Heartbeat,
    RevokedError,
    Task,
    tls_verify,
)
from site_connector.config import ConnectorConfig
from site_connector.traffic import TrafficMeter
from site_connector.tunnel import ChiselTunnel

log = logging.getLogger(__name__)

EXIT_OK = 0
EXIT_CONFIG = 2  # cấu hình sai hoặc mã kích hoạt bị từ chối: chạy lại cũng không khác
EXIT_REVOKED = 3  # Core đã thu hồi bộ kết nối: cần tạo mã kích hoạt mới
MAX_RETRY_SECONDS = 60.0
RESULT_ATTEMPTS = 5


class Agent:
    def __init__(self, config: ConnectorConfig, client: CoreClient | None = None) -> None:
        self.config = config
        self.started = time.monotonic()
        ca = str(config.ca_file) if config.ca_file else None
        self.client = client or CoreClient(config.server, tls_verify(config.tls_insecure, ca))
        self.meter = TrafficMeter(urlsplit(config.server).hostname or "", config.wan_interface)
        self.tunnel: ChiselTunnel | None = None
        # Việc đã nhận (đang chạy hoặc đã xong) – Core có thể gửi lại cho tới khi nhận kết quả
        self._seen_tasks: OrderedDict[str, None] = OrderedDict()
        self._task_runners: set[asyncio.Task[None]] = set()

    async def run(self) -> int:
        try:
            credentials = await self._credentials()
            if credentials is None:
                return EXIT_CONFIG
            self.client.use_credentials(credentials)
            self.tunnel = ChiselTunnel(
                self.config.chisel_bin,
                credentials.tunnel_url,
                credentials.tunnel_user,
                credentials.tunnel_password,
                tls_insecure=self.config.tls_insecure,
                ca_file=str(self.config.ca_file) if self.config.ca_file else None,
                fingerprint=self.config.chisel_fingerprint,
            )
            try:
                await self._heartbeat_loop()
            except RevokedError:
                log.error(
                    "Bộ kết nối %s đã bị thu hồi hoặc secret không còn hợp lệ (HTTP 401). Đã dừng đường hầm; "
                    "tạo bộ kết nối/mã kích hoạt mới trên giao diện CamHub rồi chạy lại với CAMHUB_ACTIVATION_CODE.",
                    credentials.connector_id,
                )
                state.mark_revoked(self.config.credentials_file, credentials)
                return EXIT_REVOKED
            return EXIT_OK
        finally:
            for runner in self._task_runners:
                runner.cancel()
            if self.tunnel is not None:
                await self.tunnel.stop()
            await self.client.aclose()

    # ------------------------------------------------------------------ kích hoạt
    async def _credentials(self) -> state.Credentials | None:
        try:
            stored = state.load(self.config.credentials_file)
        except ValueError as e:
            log.error("%s", e)
            return None
        if stored.credentials is not None:
            log.info("Dùng thông tin đăng nhập đã lưu của bộ kết nối %s", stored.credentials.connector_id)
            return stored.credentials
        code = self.config.activation_code.strip()
        if not code:
            log.error("Chưa kích hoạt: đặt CAMHUB_ACTIVATION_CODE bằng mã lấy khi tạo bộ kết nối trên giao diện CamHub")
            return None
        if stored.revoked_code_sha256 and stored.revoked_code_sha256 == state.code_hash(code):
            log.error("Bộ kết nối đã bị thu hồi và mã kích hoạt này đã dùng; cần mã kích hoạt mới")
            return None
        hostname = socket.gethostname()
        delay = 2.0
        while True:
            try:
                connector_id, secret, url, user, password = await self.client.activate(code, hostname, __version__)
                break
            except ActivationError as e:
                reason = {
                    "INVALID_CODE": "mã kích hoạt không đúng",
                    "ACTIVATION_EXPIRED": "mã kích hoạt đã quá hạn 24 giờ",
                    "ALREADY_ACTIVATED": "mã kích hoạt đã được dùng",
                }.get(e.code, "Core từ chối")
                log.error("Kích hoạt thất bại: %s (%s). Tạo mã mới trên giao diện CamHub.", reason, e)
                return None
            except ApiError as e:
                log.warning("Chưa kích hoạt được (%s), thử lại sau %.0f giây", e, delay)
                await asyncio.sleep(delay)
                delay = min(delay * 2, MAX_RETRY_SECONDS)
        credentials = state.Credentials(
            connector_id=connector_id,
            secret=secret,
            tunnel_url=url,
            tunnel_user=user,
            tunnel_password=password,
            activation_code_sha256=state.code_hash(code),
        )
        state.save_credentials(self.config.credentials_file, credentials)
        log.info("Đã kích hoạt bộ kết nối %s; thông tin đăng nhập lưu ở %s", connector_id, self.config.credentials_file)
        return credentials

    # ------------------------------------------------------------------ heartbeat
    async def _heartbeat_loop(self) -> None:
        assert self.tunnel is not None
        failures = 0
        while True:
            traffic = await asyncio.to_thread(self.meter.sample)
            beat = Heartbeat(
                version=__version__,
                uptime_seconds=int(time.monotonic() - self.started),
                tunnel_up=self.tunnel.up,
                rx_bytes=traffic.rx_bytes,
                tx_bytes=traffic.tx_bytes,
                rx_kbps=traffic.rx_kbps,
                tx_kbps=traffic.tx_kbps,
            )
            try:
                response = await self.client.heartbeat(beat)
            except ApiError as e:
                failures += 1
                # Mất liên lạc với Core thì vẫn giữ đường hầm theo cấu hình cũ
                delay = min(self.config.heartbeat_seconds, 5.0 * failures)
                log.warning("Heartbeat lỗi (%s), thử lại sau %.0f giây", e, delay)
                await asyncio.sleep(delay)
                continue
            if failures:
                log.info("Đã liên lạc lại với Core")
            failures = 0
            self._write_health(beat)
            if response.config is not None:
                await self.tunnel.apply(response.config)
            for task in response.tasks:
                self._start_task(task)
            await asyncio.sleep(self.config.heartbeat_seconds)

    def _write_health(self, beat: Heartbeat) -> None:
        data = {"lastHeartbeat": time.time(), "tunnelUp": beat.tunnel_up}
        try:
            self.config.health_file.write_text(json.dumps(data), encoding="utf-8")
        except OSError as e:
            log.debug("Không ghi được %s: %s", self.config.health_file, e)

    # ------------------------------------------------------------------ việc được giao
    def _start_task(self, task: Task) -> None:
        if task.id in self._seen_tasks:
            return
        self._seen_tasks[task.id] = None
        while len(self._seen_tasks) > 500:
            self._seen_tasks.popitem(last=False)
        runner = asyncio.create_task(self._run_task(task), name=f"task-{task.id}")
        self._task_runners.add(runner)
        runner.add_done_callback(self._task_runners.discard)

    async def _run_task(self, task: Task) -> None:
        devices: list[dict[str, Any]] = []
        error: str | None = None
        if task.type == "DISCOVERY":
            timeout = min(max(task.timeout_seconds, 1.0), 60.0)
            log.info("Việc %s: dò WS-Discovery trong %.0f giây", task.id, timeout)
            try:
                found = await discovery.discover(timeout, self.config.discovery_target_list())
                devices = [d.to_json() for d in found]
            except (OSError, ValueError) as e:
                error = f"Dò thiết bị thất bại: {e}"
                log.warning("Việc %s: %s", task.id, error)
        else:
            error = f"Không hỗ trợ loại việc {task.type}"
            log.warning("Việc %s: %s", task.id, error)
        await self._post_result(task, devices, error)

    async def _post_result(self, task: Task, devices: list[dict[str, Any]], error: str | None) -> None:
        delay = 2.0
        for attempt in range(1, RESULT_ATTEMPTS + 1):
            try:
                rejected = await self.client.post_task_result(task.id, devices, error)
            except RevokedError:
                return  # heartbeat sẽ phát hiện và dừng bộ kết nối
            except ApiError as e:
                log.warning("Gửi kết quả việc %s lỗi (lần %d): %s", task.id, attempt, e)
                await asyncio.sleep(delay)
                delay *= 2
                continue
            if rejected:
                log.warning("Core không nhận kết quả việc %s: %s", task.id, rejected)
            else:
                log.info("Đã gửi kết quả việc %s: %d thiết bị", task.id, len(devices))
            return
