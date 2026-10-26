"""Giả lập Telegram Bot API và Zalo OA API cho dev/e2e (kênh thông báo US-36.x).

Chỉ cài phần CamHub dùng:

- Telegram: ``POST /bot<token>/sendMessage`` và ``POST /bot<token>/sendPhoto``.
- Zalo OA (v3): ``POST /v3.0/oa/message/cs`` với header ``access_token``.
- Kiểm tra: ``GET /messages`` (mới nhất trước), ``DELETE /messages``.

Người nhận đặc biệt để thử lỗi/thử lại: ``chat_id`` = ``fail`` (Telegram 400), ``flood`` (Telegram 429);
``user_id`` = ``fail`` (Zalo -230). Token Telegram chứa ``invalid`` → 401. Thiếu ``access_token`` Zalo → -216.
Dữ liệu chỉ nằm trong bộ nhớ (tối đa ``CHAT_MOCK_MAX_MESSAGES`` tin).
"""

from __future__ import annotations

import itertools
import json
import os
import time
import uuid
from collections import deque
from datetime import UTC, datetime
from typing import Any, Literal

from fastapi import FastAPI, Header, Query, Request
from fastapi.responses import JSONResponse
from starlette.datastructures import UploadFile

MAX_MESSAGES = int(os.environ.get("CHAT_MOCK_MAX_MESSAGES", "1000"))
MAX_PHOTO_BYTES = 10 * 1024 * 1024  # Telegram giới hạn ảnh 10 MB

app = FastAPI(title="CamHub chat mock (Telegram/Zalo)", version="0.1.0")

_messages: deque[dict[str, Any]] = deque(maxlen=MAX_MESSAGES)
_telegram_ids = itertools.count(1)


def _record(channel: str, method: str, chat: str, token: str | None, **fields: Any) -> dict[str, Any]:
    entry: dict[str, Any] = {
        "id": str(uuid.uuid4()),
        "channel": channel,
        "method": method,
        "chat": chat,
        "token": token,
        "text": None,
        "parse_mode": None,
        "has_photo": False,
        "photo_size": None,
        "photo_content_type": None,
        "received_at": datetime.now(UTC).isoformat().replace("+00:00", "Z"),
    }
    entry.update(fields)
    _messages.append(entry)
    return entry


async def _params(request: Request) -> dict[str, Any]:
    """Tham số Telegram có thể gửi dạng JSON, form urlencoded hoặc multipart như API thật."""
    ctype = request.headers.get("content-type", "")
    if ctype.startswith("application/json"):
        try:
            data = await request.json()
        except ValueError:
            return {}
        return data if isinstance(data, dict) else {}
    if ctype.startswith(("multipart/form-data", "application/x-www-form-urlencoded")):
        form = await request.form(max_part_size=MAX_PHOTO_BYTES)
        return dict(form.items())
    return dict(request.query_params)


def _tg_error(status: int, description: str, **extra: Any) -> JSONResponse:
    return JSONResponse({"ok": False, "error_code": status, "description": description, **extra}, status_code=status)


def _tg_check(token: str, chat_id: Any) -> JSONResponse | None:
    if "invalid" in token:
        return _tg_error(401, "Unauthorized")
    if chat_id is None or str(chat_id).strip() == "":
        return _tg_error(400, "Bad Request: chat_id is empty")
    if str(chat_id) == "fail":
        return _tg_error(400, "Bad Request: chat not found")
    if str(chat_id) == "flood":
        return _tg_error(429, "Too Many Requests: retry after 1", parameters={"retry_after": 1})
    return None


def _tg_chat(chat_id: Any) -> dict[str, Any]:
    cid: int | str = int(chat_id) if str(chat_id).lstrip("-").isdigit() else str(chat_id)
    return {"id": cid, "type": "private" if isinstance(cid, int) and cid > 0 else "channel"}


# ---------------------------------------------------------------- Telegram Bot API


@app.post("/bot{token}/sendMessage")
async def telegram_send_message(token: str, request: Request) -> JSONResponse:
    p = await _params(request)
    if (err := _tg_check(token, p.get("chat_id"))) is not None:
        return err
    text = p.get("text")
    if not isinstance(text, str) or not text.strip():
        return _tg_error(400, "Bad Request: message text is empty")
    message_id = next(_telegram_ids)
    _record("telegram", "sendMessage", str(p["chat_id"]), token, text=text, parse_mode=p.get("parse_mode"))
    result = {"message_id": message_id, "date": int(time.time()), "chat": _tg_chat(p["chat_id"]), "text": text}
    return JSONResponse({"ok": True, "result": result})


@app.post("/bot{token}/sendPhoto")
async def telegram_send_photo(token: str, request: Request) -> JSONResponse:
    p = await _params(request)
    if (err := _tg_check(token, p.get("chat_id"))) is not None:
        return err
    photo = p.get("photo")
    size: int | None = None
    content_type: str | None = None
    if isinstance(photo, UploadFile):
        data = await photo.read()
        size, content_type = len(data), photo.content_type
        if size == 0:
            return _tg_error(400, "Bad Request: file must be non-empty")
    elif isinstance(photo, str) and photo:
        content_type = "url/file_id"  # ảnh gửi bằng URL hoặc file_id: không tải về
    else:
        return _tg_error(400, "Bad Request: there is no photo in the request")
    caption = p.get("caption")
    message_id = next(_telegram_ids)
    _record(
        "telegram",
        "sendPhoto",
        str(p["chat_id"]),
        token,
        text=caption,
        parse_mode=p.get("parse_mode"),
        has_photo=True,
        photo_size=size,
        photo_content_type=content_type,
    )
    file_id = f"mock-{uuid.uuid4().hex}"
    result: dict[str, Any] = {
        "message_id": message_id,
        "date": int(time.time()),
        "chat": _tg_chat(p["chat_id"]),
        "photo": [
            {"file_id": file_id, "file_unique_id": file_id[-16:], "width": 1280, "height": 720, "file_size": size or 0}
        ],
    }
    if caption:
        result["caption"] = caption
    return JSONResponse({"ok": True, "result": result})


# ---------------------------------------------------------------- Zalo OA API v3


@app.post("/v3.0/oa/message/cs")
async def zalo_send_message(
    request: Request, access_token: str | None = Header(default=None, convert_underscores=False)
) -> JSONResponse:
    # Header của Zalo là ``access_token`` (gạch dưới), không đổi sang ``access-token``.
    # Zalo luôn trả HTTP 200, mã lỗi nằm trong trường ``error``
    if not access_token:
        return JSONResponse({"error": -216, "message": "Access token is invalid"})
    try:
        body = json.loads(await request.body())
    except ValueError:
        return JSONResponse({"error": -201, "message": "Body is not valid JSON"})
    recipient = body.get("recipient") if isinstance(body, dict) else None
    message = body.get("message") if isinstance(body, dict) else None
    user_id = recipient.get("user_id") if isinstance(recipient, dict) else None
    text = message.get("text") if isinstance(message, dict) else None
    if not user_id:
        return JSONResponse({"error": -201, "message": "recipient.user_id is required"})
    if str(user_id) == "fail":
        return JSONResponse({"error": -230, "message": "User has not interacted with the OA"})
    if not isinstance(text, str) or not text.strip():
        return JSONResponse({"error": -201, "message": "message.text is required"})
    message_id = uuid.uuid4().hex
    _record("zalo", "message/cs", str(user_id), access_token, text=text)
    return JSONResponse({"error": 0, "message": "Success", "data": {"message_id": message_id, "user_id": str(user_id)}})


# ---------------------------------------------------------------- Kiểm tra


@app.get("/messages")
def list_messages(
    channel: Literal["telegram", "zalo"] | None = None,
    chat: str | None = Query(default=None),
    limit: int = Query(default=100, ge=1, le=1000),
) -> list[dict[str, Any]]:
    """Tin đã nhận, mới nhất trước; lọc theo kênh và người nhận (chat_id/user_id)."""
    out = [
        m
        for m in reversed(_messages)
        if (channel is None or m["channel"] == channel) and (chat is None or m["chat"] == chat)
    ]
    return out[:limit]


@app.delete("/messages")
def clear_messages() -> dict[str, int]:
    n = len(_messages)
    _messages.clear()
    return {"deleted": n}


@app.get("/health")
def health() -> dict[str, Any]:
    return {"status": "UP", "messages": len(_messages)}
