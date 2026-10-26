"""Gọi API dành cho bộ kết nối của Core: /api/v1/connector-agent/* (không dùng JWT người dùng).

- activate: mã kích hoạt một lần -> connectorId, secret, tài khoản đường hầm
- heartbeat (Basic auth connectorId:secret) mỗi 30 giây -> cấu hình đường hầm + việc cần làm
- tasks/{id}/result: kết quả việc (DISCOVERY)
"""

from __future__ import annotations

import ssl
from dataclasses import dataclass, field
from typing import Any

import httpx

from site_connector.state import Credentials

API_PREFIX = "/api/v1/connector-agent"


class ApiError(Exception):
    """Lỗi tạm thời (mạng, 5xx): thử lại sau."""


class ActivationError(Exception):
    """Mã kích hoạt bị từ chối (INVALID_CODE, ACTIVATION_EXPIRED, ALREADY_ACTIVATED): không thử lại."""

    def __init__(self, code: str, status: int) -> None:
        super().__init__(f"{code} (HTTP {status})")
        self.code = code
        self.status = status


class RevokedError(Exception):
    """Core trả 401: bộ kết nối đã bị thu hồi hoặc secret không còn đúng."""


@dataclass(frozen=True)
class Forward:
    port: int
    host: str
    target_port: int


@dataclass(frozen=True)
class TunnelConfig:
    config_version: str
    socks_port: int
    forwards: tuple[Forward, ...] = ()


@dataclass(frozen=True)
class Task:
    id: str
    type: str
    timeout_seconds: float = 5.0


@dataclass(frozen=True)
class HeartbeatResponse:
    config: TunnelConfig | None
    tasks: list[Task] = field(default_factory=list)


@dataclass(frozen=True)
class Heartbeat:
    version: str
    uptime_seconds: int
    tunnel_up: bool
    rx_bytes: int
    tx_bytes: int
    rx_kbps: float
    tx_kbps: float

    def to_json(self) -> dict[str, Any]:
        return {
            "version": self.version,
            "uptimeSeconds": self.uptime_seconds,
            "tunnelUp": self.tunnel_up,
            "rxBytes": self.rx_bytes,
            "txBytes": self.tx_bytes,
            "rxKbps": round(self.rx_kbps, 1),
            "txKbps": round(self.tx_kbps, 1),
        }


def parse_heartbeat_response(data: Any) -> HeartbeatResponse:
    """Đọc phản hồi heartbeat; trường thiếu/sai kiểu thì ``ValueError``."""
    if not isinstance(data, dict):
        raise ValueError("phản hồi heartbeat không phải JSON object")
    config: TunnelConfig | None = None
    raw_config = data.get("config")
    if raw_config:
        forwards = tuple(
            Forward(port=int(f["port"]), host=str(f["host"]), target_port=int(f["targetPort"]))
            for f in raw_config.get("forwards") or []
        )
        config = TunnelConfig(
            config_version=str(raw_config["configVersion"]),
            socks_port=int(raw_config["socksPort"]),
            forwards=forwards,
        )
    tasks = [
        Task(id=str(t["id"]), type=str(t["type"]), timeout_seconds=float(t.get("timeoutSeconds") or 5))
        for t in data.get("tasks") or []
    ]
    return HeartbeatResponse(config=config, tasks=tasks)


def tls_verify(insecure: bool, ca_file: str | None) -> ssl.SSLContext | bool:
    if insecure:
        return False
    if ca_file:
        return ssl.create_default_context(cafile=ca_file)
    return True


def _error_code(response: httpx.Response) -> str:
    try:
        body = response.json()
    except ValueError:
        return f"HTTP_{response.status_code}"
    if isinstance(body, dict) and isinstance(body.get("code"), str):
        return str(body["code"])
    return f"HTTP_{response.status_code}"


class CoreClient:
    def __init__(
        self,
        server: str,
        verify: ssl.SSLContext | bool,
        timeout: float = 15.0,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self._http = httpx.AsyncClient(
            base_url=server + API_PREFIX, verify=verify, timeout=timeout, transport=transport
        )
        self._auth: httpx.BasicAuth | None = None

    async def aclose(self) -> None:
        await self._http.aclose()

    def use_credentials(self, credentials: Credentials) -> None:
        self._auth = httpx.BasicAuth(credentials.connector_id, credentials.secret)

    async def _post(self, path: str, body: dict[str, Any], auth: bool = True) -> httpx.Response:
        try:
            response = await self._http.post(
                path, json=body, auth=self._auth if auth and self._auth else httpx.USE_CLIENT_DEFAULT
            )
        except httpx.HTTPError as e:
            raise ApiError(f"không gọi được {path}: {e!r}") from e
        if auth and response.status_code == 401:
            raise RevokedError("Core từ chối thông tin đăng nhập (HTTP 401)")
        if response.status_code >= 500 or response.status_code == 429:
            raise ApiError(f"{path}: HTTP {response.status_code}")
        return response

    async def activate(self, code: str, hostname: str, version: str) -> tuple[str, str, str, str, str]:
        """Trả (connectorId, secret, tunnelUrl, tunnelUser, tunnelPassword)."""
        response = await self._post(
            "/activate", {"code": code.strip(), "hostname": hostname, "version": version}, auth=False
        )
        if response.status_code != 200:
            raise ActivationError(_error_code(response), response.status_code)
        try:
            data = response.json()
            tunnel = data["tunnel"]
            return (
                str(data["connectorId"]),
                str(data["secret"]),
                str(tunnel["url"]),
                str(tunnel["user"]),
                str(tunnel["password"]),
            )
        except (ValueError, KeyError, TypeError) as e:
            raise ApiError(f"phản hồi kích hoạt sai định dạng: {e!r}") from e

    async def heartbeat(self, beat: Heartbeat) -> HeartbeatResponse:
        response = await self._post("/heartbeat", beat.to_json())
        if response.status_code != 200:
            raise ApiError(f"heartbeat: HTTP {response.status_code} {_error_code(response)}")
        try:
            return parse_heartbeat_response(response.json())
        except (ValueError, KeyError, TypeError) as e:
            raise ApiError(f"phản hồi heartbeat sai định dạng: {e!r}") from e

    async def post_task_result(self, task_id: str, devices: list[dict[str, Any]], error: str | None) -> str | None:
        """Gửi kết quả. Trả ``None`` khi Core nhận; trả mã lỗi khi Core từ chối hẳn (4xx, không thử lại)."""
        response = await self._post(f"/tasks/{task_id}/result", {"devices": devices, "error": error})
        if response.status_code >= 400:
            return f"HTTP {response.status_code} {_error_code(response)}"
        return None
