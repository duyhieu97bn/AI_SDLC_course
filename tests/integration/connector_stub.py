"""Core giả cho API /api/v1/connector-agent/* – kiểm thử Site Connector + đường hầm khi chưa có Core (TS-06.4).

Chỉ dùng thư viện chuẩn. Chạy trong mạng wan của profile site (Site Connector gọi API vào đây, còn đường hầm
vẫn đi qua Nginx /tunnel/ tới dịch vụ tunnel thật) và ghi tệp xác thực chisel vào volume tunnelauth:

  docker run -d --name connector-stub --user 10001 --network camhub_wan --network-alias connector-stub \
    -v camhub_tunnelauth:/tunnel-auth -v "$PWD/deploy/nginx/certs:/certs:ro" \
    -v "$PWD/tests/integration:/stub:ro" -e STUB_CODE=SITE-TEST-1 \
    python:3.12-slim python /stub/connector_stub.py

Hành vi: mã kích hoạt STUB_CODE dùng một lần; heartbeat trả socksPort 21001 và chuyển tiếp 22001 ->
site-camera:8554, kèm một việc DISCOVERY ở heartbeat đầu; POST /stub/revoke làm mọi lời gọi sau trả 401.
"""

from __future__ import annotations

import base64
import json
import os
import secrets
import ssl
import sys
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

CODE = os.environ.get("STUB_CODE", "SITE-TEST-1")
AUTHFILE = Path(os.environ.get("STUB_AUTHFILE", "/tunnel-auth/users.json"))
TUNNEL_URL = os.environ.get("STUB_TUNNEL_URL", "https://nginx/tunnel/")
SOCKS_PORT = int(os.environ.get("STUB_SOCKS_PORT", "21001"))
FORWARDS = [{"port": 22001, "host": "site-camera", "targetPort": 8554}]
PREFIX = "/api/v1/connector-agent"

STATE: dict[str, Any] = {"used": False, "connector": None, "revoked": False, "task_sent": False, "results": []}


def log(msg: str) -> None:
    print(f"stub: {msg}", file=sys.stderr, flush=True)


def write_authfile(user: str, password: str) -> None:
    # Định dạng chisel: regex so với "R:<địa chỉ nghe>:<cổng>" (không có đích, không có ":socks")
    ports = [SOCKS_PORT] + [f["port"] for f in FORWARDS]
    users = {f"{user}:{password}": [f"^R:0\\.0\\.0\\.0:{p}$" for p in ports]}
    AUTHFILE.write_text(json.dumps(users, indent=2))
    log(f"đã ghi {AUTHFILE}: {users}")


class Handler(BaseHTTPRequestHandler):
    def _send(self, status: int, body: Any = None) -> None:
        data = b"" if body is None else json.dumps(body).encode()
        self.send_response(status)
        if body is not None:
            self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _authorized(self) -> bool:
        conn = STATE["connector"]
        if STATE["revoked"] or conn is None:
            return False
        expected = base64.b64encode(f"{conn['id']}:{conn['secret']}".encode()).decode()
        return self.headers.get("Authorization") == f"Basic {expected}"

    def do_POST(self) -> None:  # noqa: N802 - tên do http.server quy định
        length = int(self.headers.get("Content-Length") or 0)
        body = json.loads(self.rfile.read(length) or b"null")
        path = self.path
        if path == "/stub/revoke":
            STATE["revoked"] = True
            log("đã thu hồi bộ kết nối")
            return self._send(204)
        if path == f"{PREFIX}/activate":
            log(f"activate {body}")
            if body.get("code") != CODE:
                return self._send(404, {"code": "INVALID_CODE"})
            if STATE["used"]:
                return self._send(409, {"code": "ALREADY_ACTIVATED"})
            STATE["used"] = True
            conn = {"id": str(uuid.uuid4()), "secret": secrets.token_hex(24)}
            user, password = f"conn-{conn['id'][:8]}", secrets.token_hex(24)
            STATE["connector"] = conn
            write_authfile(user, password)
            return self._send(
                200,
                {
                    "connectorId": conn["id"],
                    "secret": conn["secret"],
                    "tunnel": {"url": TUNNEL_URL, "user": user, "password": password},
                },
            )
        if not self._authorized():
            return self._send(401, {"code": "UNAUTHORIZED"})
        if path == f"{PREFIX}/heartbeat":
            log(f"heartbeat {body}")
            tasks = []
            if not STATE["task_sent"]:
                STATE["task_sent"] = True
                tasks = [{"id": str(uuid.uuid4()), "type": "DISCOVERY", "timeoutSeconds": 3}]
            config = {"configVersion": "1", "socksPort": SOCKS_PORT, "forwards": FORWARDS}
            return self._send(200, {"config": config, "tasks": tasks})
        if path.startswith(f"{PREFIX}/tasks/") and path.endswith("/result"):
            log(f"kết quả việc {path}: {json.dumps(body, ensure_ascii=False)}")
            STATE["results"].append(body)
            return self._send(204)
        return self._send(404, {"code": "NOT_FOUND"})

    def do_GET(self) -> None:  # noqa: N802
        if self.path == "/stub/state":
            return self._send(200, {k: v for k, v in STATE.items() if k != "connector"})
        return self._send(404)

    def log_message(self, format: str, *args: Any) -> None:  # noqa: A002
        pass


def main() -> None:
    server = ThreadingHTTPServer(("0.0.0.0", int(os.environ.get("STUB_PORT", "443"))), Handler)  # noqa: S104
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(
        os.environ.get("STUB_CERT", "/certs/camhub.crt"), os.environ.get("STUB_KEY", "/certs/camhub.key")
    )
    server.socket = ctx.wrap_socket(server.socket, server_side=True)
    log(f"lắng nghe HTTPS cổng {server.server_address[1]}, mã kích hoạt {CODE}")
    server.serve_forever()


if __name__ == "__main__":
    main()
