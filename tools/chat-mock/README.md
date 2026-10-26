# chat-mock – giả lập Telegram / Zalo cho dev và e2e

Dịch vụ nhỏ (FastAPI, chạy non-root, chỉ lưu trong bộ nhớ) cài **đúng phần** Telegram Bot API và Zalo OA API
mà Core dùng để gửi thông báo, kèm API kiểm tra để test e2e đọc lại tin đã gửi. Không gọi ra Internet.

```bash
cd deploy
docker compose --profile sim up -d --build chat-mock
curl -s http://127.0.0.1:8026/health
```

Trong mạng `backend`, cấu hình Core dùng base URL `http://chat-mock:8026` cho cả Telegram và Zalo.

## Telegram Bot API

| Phương thức | Đầu vào | Kết quả |
|---|---|---|
| `POST /bot<token>/sendMessage` | JSON (hoặc form): `chat_id`, `text`, `parse_mode`, `disable_web_page_preview` | `{"ok": true, "result": {"message_id": n, "date": ..., "chat": {...}, "text": ...}}` |
| `POST /bot<token>/sendPhoto` | multipart: `chat_id`, `caption`, `parse_mode`, `photo` (file); `photo` dạng chuỗi (URL/file_id) cũng nhận | `{"ok": true, "result": {"message_id": n, "photo": [...], "caption": ...}}` |

Người nhận/token đặc biệt để thử lỗi và thử lại:

| Điều kiện | HTTP | Thân |
|---|---|---|
| `chat_id` = `fail` | 400 | `{"ok": false, "error_code": 400, "description": "Bad Request: chat not found"}` |
| `chat_id` = `flood` | 429 | `{"ok": false, "error_code": 429, "description": "Too Many Requests: retry after 1", "parameters": {"retry_after": 1}}` |
| token chứa `invalid` | 401 | `{"ok": false, "error_code": 401, "description": "Unauthorized"}` |
| thiếu `text` / `photo` | 400 | `{"ok": false, ...}` |

## Zalo OA API (v3)

`POST /v3.0/oa/message/cs`, header `access_token: <token>`, JSON
`{"recipient": {"user_id": "..."}, "message": {"text": "..."}}` →
`{"error": 0, "message": "Success", "data": {"message_id": "...", "user_id": "..."}}`.

Như Zalo thật, lỗi vẫn trả HTTP 200 với `error` khác 0:

| Điều kiện | Thân |
|---|---|
| `user_id` = `fail` | `{"error": -230, "message": "User has not interacted with the OA"}` |
| thiếu header `access_token` | `{"error": -216, "message": "Access token is invalid"}` |
| thiếu `user_id` / `text`, JSON hỏng | `{"error": -201, ...}` |

## Kiểm tra

- `GET /messages?channel=telegram|zalo&chat=<chat_id|user_id>&limit=100` – tin đã nhận, **mới nhất trước**. Mỗi tin:
  `id`, `channel`, `method` (`sendMessage`/`sendPhoto`/`message/cs`), `chat`, `token`, `text` (nội dung hoặc caption),
  `parse_mode`, `has_photo`, `photo_size` (byte), `photo_content_type`, `received_at` (UTC ISO-8601).
- `DELETE /messages` – xóa hết (`{"deleted": n}`), gọi ở đầu mỗi kịch bản e2e.
- `GET /health`.

Lỗi (`fail`, `flood`, ...) không được ghi vào `/messages`. Giữ tối đa `CHAT_MOCK_MAX_MESSAGES` (mặc định 1000) tin.

## Kiểm thử

```bash
pip install -e ".[dev]" && pytest -q && ruff check .
```
