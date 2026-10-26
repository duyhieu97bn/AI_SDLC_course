"""Quản lý tiến trình chisel client: đường hầm ngược qua WSS 443 tới Tunnel Server (ADR-10).

Remote mở trên Tunnel Server (đều nghe ở 0.0.0.0 trong container tunnel, chỉ mạng backend tới được):
  R:0.0.0.0:<socksPort>:socks                   SOCKS5 ngược cho ONVIF (Device Gateway dùng socks5://tunnel:<socksPort>)
  R:0.0.0.0:<port>:<host>:<targetPort>          chuyển tiếp TCP ngược cho RTSP (MediaMTX kéo rtsp://tunnel:<port>/...)
Phải ghi rõ 0.0.0.0: với "R:<port>:socks" chisel mặc định chỉ nghe ở 127.0.0.1 của Tunnel Server.

Mật khẩu đường hầm truyền qua biến môi trường AUTH (không lộ trong danh sách tiến trình).
Trạng thái kết nối đọc từ log của chisel: "Connected" -> lên; "Disconnected"/"Connection error"/"Retrying" -> xuống.
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
import os
import time

from site_connector.api import TunnelConfig

log = logging.getLogger(__name__)

MIN_BACKOFF = 1.0
MAX_BACKOFF = 60.0
# Kết nối ổn định lâu hơn mức này thì đặt lại thời gian chờ khởi động lại
STABLE_SECONDS = 60.0
_DOWN_MARKERS = ("Disconnected", "Connection error", "Retrying in", "Connecting to")


def build_remotes(config: TunnelConfig) -> list[str]:
    remotes = [f"R:0.0.0.0:{config.socks_port}:socks"]
    for f in config.forwards:
        host = f"[{f.host}]" if ":" in f.host else f.host
        remotes.append(f"R:0.0.0.0:{f.port}:{host}:{f.target_port}")
    return remotes


def build_command(
    chisel_bin: str,
    url: str,
    config: TunnelConfig,
    *,
    tls_insecure: bool = False,
    ca_file: str | None = None,
    fingerprint: str = "",
) -> list[str]:
    cmd = [chisel_bin, "client", "--keepalive", "25s", "--max-retry-interval", "30s"]
    if tls_insecure:
        cmd.append("--tls-skip-verify")
    elif ca_file:
        cmd += ["--tls-ca", ca_file]
    if fingerprint:
        cmd += ["--fingerprint", fingerprint]
    return [*cmd, url, *build_remotes(config)]


def parse_state(line: str) -> bool | None:
    """Đổi một dòng log chisel thành trạng thái đường hầm: True lên, False xuống, None không đổi."""
    if "client: Connected" in line:
        return True
    if any(marker in line for marker in _DOWN_MARKERS):
        return False
    return None


class ChiselTunnel:
    """Giữ một tiến trình chisel client chạy theo cấu hình hiện tại; tự khởi động lại với backoff."""

    def __init__(
        self,
        chisel_bin: str,
        url: str,
        user: str,
        password: str,
        *,
        tls_insecure: bool = False,
        ca_file: str | None = None,
        fingerprint: str = "",
    ) -> None:
        self._chisel_bin = chisel_bin
        self._url = url
        self._auth = f"{user}:{password}"
        self._tls_insecure = tls_insecure
        self._ca_file = ca_file
        self._fingerprint = fingerprint
        self._config: TunnelConfig | None = None
        self._runner: asyncio.Task[None] | None = None
        self._proc: asyncio.subprocess.Process | None = None
        self.up = False

    @property
    def config_version(self) -> str | None:
        return self._config.config_version if self._config else None

    async def apply(self, config: TunnelConfig) -> None:
        """Áp cấu hình mới; chỉ khởi động lại chisel khi configVersion đổi."""
        if self._config is not None and self._config.config_version == config.config_version:
            return
        log.info(
            "Cấu hình đường hầm %s: %s",
            config.config_version,
            " ".join(build_remotes(config)),
        )
        await self.stop()
        self._config = config
        self._runner = asyncio.create_task(self._run(config), name="chisel")

    async def stop(self) -> None:
        runner, self._runner = self._runner, None
        if runner is not None:
            runner.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await runner
        self.up = False

    async def _run(self, config: TunnelConfig) -> None:
        backoff = MIN_BACKOFF
        cmd = build_command(
            self._chisel_bin,
            self._url,
            config,
            tls_insecure=self._tls_insecure,
            ca_file=self._ca_file,
            fingerprint=self._fingerprint,
        )
        env = {**os.environ, "AUTH": self._auth}
        while True:
            started = time.monotonic()
            try:
                await self._run_once(cmd, env)
            except OSError as e:
                log.error("Không chạy được chisel (%s): %s", self._chisel_bin, e)
            finally:
                self.up = False
            if time.monotonic() - started > STABLE_SECONDS:
                backoff = MIN_BACKOFF
            log.warning("chisel đã thoát, khởi động lại sau %.0f giây", backoff)
            await asyncio.sleep(backoff)
            backoff = min(backoff * 2, MAX_BACKOFF)

    async def _run_once(self, cmd: list[str], env: dict[str, str]) -> None:
        proc = await asyncio.create_subprocess_exec(
            *cmd, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT, env=env
        )
        self._proc = proc
        try:
            assert proc.stdout is not None
            async for raw in proc.stdout:
                line = raw.decode(errors="replace").rstrip()
                state = parse_state(line)
                if state is not None and state != self.up:
                    self.up = state
                    log.info("Đường hầm %s", "đã kết nối" if state else "mất kết nối")
                log.debug("chisel: %s", line)
                if "Connection error" in line or "denied" in line:
                    log.warning("chisel: %s", line)
            code = await proc.wait()
            log.warning("chisel thoát với mã %s", code)
        finally:
            if proc.returncode is None:
                proc.terminate()
                try:
                    await asyncio.wait_for(proc.wait(), timeout=5)
                except TimeoutError:
                    proc.kill()
                    await proc.wait()
            self._proc = None
