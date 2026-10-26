"""Xác thực lệnh nội bộ từ Core bằng header ``X-Internal-Token`` (so sánh thời gian hằng)."""

from __future__ import annotations

import hmac

from starlette.responses import JSONResponse
from starlette.types import ASGIApp, Receive, Scope, Send

HEADER = b"x-internal-token"


def token_matches(provided: bytes | None, expected: bytes) -> bool:
    return provided is not None and hmac.compare_digest(provided, expected)


class InternalTokenMiddleware:
    """Chặn mọi yêu cầu tới ``prefix`` (mặc định ``/v1``) thiếu hoặc sai token, trước khi đọc body.

    Token rỗng nghĩa là tắt kiểm tra (chỉ dùng trong mạng dev nội bộ).
    """

    def __init__(self, app: ASGIApp, token: str, prefix: str = "/v1") -> None:
        self.app = app
        self._token = token.encode("utf-8")
        self._prefix = prefix

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] == "http" and self._token and str(scope.get("path", "")).startswith(self._prefix):
            provided = next((v for k, v in scope.get("headers", []) if k.lower() == HEADER), None)
            if not token_matches(provided, self._token):
                response = JSONResponse(
                    {"code": "GATEWAY_UNAUTHORIZED", "detail": "Thiếu hoặc sai X-Internal-Token"}, status_code=401
                )
                await response(scope, receive, send)
                return
        await self.app(scope, receive, send)
