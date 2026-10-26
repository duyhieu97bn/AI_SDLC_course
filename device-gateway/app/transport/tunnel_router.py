"""Định tuyến lời gọi thiết bị qua tunnel site (TS-06.3).

Camera ở site xa chỉ tới được qua proxy SOCKS5 ngược do tunnel server mở trong mạng backend, ví dụ
``socks5://tunnel:21001``. Core gửi ``proxy`` kèm địa chỉ thiết bị; mọi lời gọi HTTP(S) tới thiết bị đó
(SOAP, XAddr dịch vụ, PullPoint, URI ảnh chụp) và kết nối TCP kiểm tra RTSP đều đi qua proxy.

- Chỉ nhận ``socks5://host:port`` hoặc ``socks5h://host:port`` (không tài khoản, không path).
- Địa chỉ thiết bị (kể cả XAddr LAN như ``http://192.168.1.64/onvif/...``) được gửi nguyên cho proxy trong
  lệnh CONNECT; tên miền luôn được phân giải ở phía connector (httpx xử lý ``socks5`` và ``socks5h`` như nhau
  ở điểm này), nên địa chỉ LAN của site không cần tồn tại trong mạng của Gateway.
- Mỗi URL proxy dùng một ``httpx.AsyncClient`` riêng, tạo khi cần và giữ lại (connection pool theo site).
"""

from __future__ import annotations

import asyncio
import contextlib
import ipaddress
import logging
import struct
from typing import Final
from urllib.parse import urlsplit

import httpx

log = logging.getLogger(__name__)

PROXY_SCHEMES: Final = ("socks5", "socks5h")
_PROXY_HINT = "proxy chỉ nhận dạng socks5://host:port hoặc socks5h://host:port"


def normalize_proxy(value: str | None) -> str | None:
    """Kiểm tra và chuẩn hóa URL proxy; rỗng hoặc ``None`` nghĩa là gọi trực tiếp.

    Ném ``ValueError`` khi sai dạng (dùng được làm validator của pydantic).
    """
    if value is None or not value.strip():
        return None
    raw = value.strip()
    parts = urlsplit(raw)
    scheme = parts.scheme.lower()
    if scheme not in PROXY_SCHEMES:
        raise ValueError(_PROXY_HINT)
    if parts.username is not None or parts.password is not None:
        raise ValueError(f"{_PROXY_HINT} (không kèm tài khoản)")
    if parts.path not in ("", "/") or parts.query or parts.fragment:
        raise ValueError(_PROXY_HINT)
    try:
        port = parts.port
    except ValueError as e:
        raise ValueError(f"{_PROXY_HINT} (cổng không hợp lệ)") from e
    host = parts.hostname
    if not host or port is None or port == 0:
        raise ValueError(_PROXY_HINT)
    if ":" in host:
        try:
            ipaddress.IPv6Address(host)
        except ValueError as e:
            raise ValueError(_PROXY_HINT) from e
        host = f"[{host}]"
    return f"{scheme}://{host}:{port}"


class SocksError(OSError):
    """Proxy SOCKS5 từ chối hoặc trả lời sai giao thức."""


_SOCKS_REPLIES: Final[dict[int, str]] = {
    1: "lỗi chung của proxy",
    2: "proxy không cho phép kết nối",
    3: "mạng không tới được",
    4: "thiết bị không tới được",
    5: "thiết bị từ chối kết nối",
    6: "hết TTL",
    7: "lệnh không hỗ trợ",
    8: "kiểu địa chỉ không hỗ trợ",
}


def _socks_address(host: str) -> bytes:
    try:
        ip = ipaddress.ip_address(host)
    except ValueError:
        name = host.encode("idna")
        if len(name) > 255:
            raise SocksError("Tên thiết bị quá dài cho SOCKS5") from None
        return b"\x03" + bytes([len(name)]) + name
    return (b"\x01" if ip.version == 4 else b"\x04") + ip.packed


async def socks5_connect(proxy: str, host: str, port: int) -> tuple[asyncio.StreamReader, asyncio.StreamWriter]:
    """Mở kết nối TCP tới ``host:port`` qua proxy SOCKS5 (không xác thực, lệnh CONNECT).

    Dùng cho kiểm tra RTSP; HTTP đi qua ``httpx`` có sẵn hỗ trợ SOCKS.
    """
    parts = urlsplit(proxy)
    if parts.hostname is None or parts.port is None:
        raise SocksError("URL proxy không hợp lệ")
    reader, writer = await asyncio.open_connection(parts.hostname, parts.port)
    try:
        writer.write(b"\x05\x01\x00")
        await writer.drain()
        version, method = await reader.readexactly(2)
        if version != 5 or method != 0:
            raise SocksError("Proxy SOCKS5 yêu cầu phương thức xác thực không hỗ trợ")
        writer.write(b"\x05\x01\x00" + _socks_address(host) + struct.pack("!H", port))
        await writer.drain()
        version, reply, _, atyp = await reader.readexactly(4)
        if version != 5:
            raise SocksError("Proxy không trả lời giao thức SOCKS5")
        if reply != 0:
            raise SocksError(f"Proxy SOCKS5: {_SOCKS_REPLIES.get(reply, f'mã lỗi {reply}')}")
        if atyp == 1:
            await reader.readexactly(4 + 2)
        elif atyp == 4:
            await reader.readexactly(16 + 2)
        elif atyp == 3:
            (length,) = await reader.readexactly(1)
            await reader.readexactly(length + 2)
        else:
            raise SocksError("Proxy SOCKS5 trả kiểu địa chỉ không hợp lệ")
    except (asyncio.IncompleteReadError, SocksError) as e:
        writer.close()
        with contextlib.suppress(OSError):
            await writer.wait_closed()
        if isinstance(e, SocksError):
            raise
        raise SocksError("Proxy SOCKS5 đóng kết nối giữa chừng") from e
    except BaseException:
        writer.close()
        raise
    return reader, writer


class TunnelRouter:
    """Chọn ``httpx.AsyncClient`` theo proxy: ``None`` → client trực tiếp, ngược lại client SOCKS5 (nhớ theo URL)."""

    def __init__(
        self,
        direct: httpx.AsyncClient,
        *,
        verify: bool = False,
        max_connections_per_proxy: int = 50,
    ) -> None:
        self._direct = direct
        self._verify = verify
        self._limits = httpx.Limits(
            max_connections=max_connections_per_proxy, max_keepalive_connections=max(1, max_connections_per_proxy // 4)
        )
        self._clients: dict[str, httpx.AsyncClient] = {}

    @property
    def direct(self) -> httpx.AsyncClient:
        return self._direct

    def client(self, proxy: str | None) -> httpx.AsyncClient:
        url = normalize_proxy(proxy)
        if url is None:
            return self._direct
        cached = self._clients.get(url)
        if cached is None:
            cached = httpx.AsyncClient(
                proxy=url,
                verify=self._verify,
                trust_env=False,
                follow_redirects=False,
                limits=self._limits,
            )
            self._clients[url] = cached
            log.info("Tạo kết nối tới thiết bị qua tunnel %s", url)
        return cached

    async def open_connection(
        self, host: str, port: int, proxy: str | None
    ) -> tuple[asyncio.StreamReader, asyncio.StreamWriter]:
        """Kết nối TCP tới thiết bị, qua proxy SOCKS5 nếu có."""
        url = normalize_proxy(proxy)
        if url is None:
            return await asyncio.open_connection(host, port)
        return await socks5_connect(url, host, port)

    @property
    def proxies(self) -> list[str]:
        return list(self._clients)

    async def aclose(self) -> None:
        """Đóng các client qua proxy; client trực tiếp do nơi tạo ra tự đóng."""
        clients, self._clients = list(self._clients.values()), {}
        for client in clients:
            with contextlib.suppress(Exception):
                await client.aclose()
