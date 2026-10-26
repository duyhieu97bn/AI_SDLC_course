"""Proxy SOCKS5 tối giản chạy trong tiến trình test, đóng vai tunnel site (TS-06.3).

``hosts`` ánh xạ tên/địa chỉ "LAN của site" sang địa chỉ thật trên máy test, nên Gateway chỉ tới được thiết bị
khi đi qua proxy. Proxy ghi lại mọi đích CONNECT để test kiểm tra lưu lượng thực sự đi qua tunnel.
"""

from __future__ import annotations

import asyncio
import contextlib
import ipaddress
import struct
import threading


class Socks5Server:
    def __init__(self, hosts: dict[str, str] | None = None) -> None:
        self.hosts = hosts or {}
        self.requests: list[tuple[str, int]] = []
        self.port = 0
        self._loop = asyncio.new_event_loop()
        self._server: asyncio.Server | None = None
        self._thread = threading.Thread(target=self._loop.run_forever, daemon=True)

    @property
    def url(self) -> str:
        return f"socks5://127.0.0.1:{self.port}"

    def start(self) -> Socks5Server:
        self._thread.start()
        future = asyncio.run_coroutine_threadsafe(asyncio.start_server(self._handle, "127.0.0.1", 0), self._loop)
        self._server = future.result(timeout=5)
        self.port = int(self._server.sockets[0].getsockname()[1])
        return self

    def stop(self) -> None:
        async def close() -> None:
            if self._server is not None:
                self._server.close()
            tasks = [t for t in asyncio.all_tasks() if t is not asyncio.current_task()]
            for task in tasks:
                task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)

        asyncio.run_coroutine_threadsafe(close(), self._loop).result(timeout=5)
        self._loop.call_soon_threadsafe(self._loop.stop)
        self._thread.join(timeout=5)
        self._loop.close()

    async def _handle(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        upstream: asyncio.StreamWriter | None = None
        try:
            _, count = await reader.readexactly(2)
            await reader.readexactly(count)
            writer.write(b"\x05\x00")
            _, cmd, _, atyp = await reader.readexactly(4)
            if atyp == 1:
                host = str(ipaddress.IPv4Address(await reader.readexactly(4)))
            elif atyp == 4:
                host = str(ipaddress.IPv6Address(await reader.readexactly(16)))
            else:
                (length,) = await reader.readexactly(1)
                host = (await reader.readexactly(length)).decode()
            (port,) = struct.unpack("!H", await reader.readexactly(2))
            self.requests.append((host, port))
            try:
                if cmd != 1:
                    raise OSError("chỉ hỗ trợ CONNECT")
                up_reader, upstream = await asyncio.open_connection(self.hosts.get(host, host), port)
            except OSError:
                writer.write(b"\x05\x05\x00\x01" + bytes(6))
                await writer.drain()
                return
            writer.write(b"\x05\x00\x00\x01" + bytes(6))
            await writer.drain()
            await asyncio.gather(_pipe(reader, upstream), _pipe(up_reader, writer))
        except (asyncio.IncompleteReadError, ConnectionError):
            pass
        finally:
            for w in (writer, upstream):
                if w is not None:
                    w.close()


async def _pipe(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
    try:
        while data := await reader.read(65536):
            writer.write(data)
            await writer.drain()
    except ConnectionError:
        pass
    finally:
        with contextlib.suppress(OSError, RuntimeError):
            writer.write_eof()
