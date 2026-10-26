"""Log JSON một dòng mỗi sự kiện, cùng định dạng với Core để Loki đọc chung (TS-07)."""

import json
import logging
from datetime import UTC, datetime
from typing import Any


class JsonFormatter(logging.Formatter):
    def __init__(self, app: str) -> None:
        super().__init__()
        self.app = app

    def format(self, record: logging.LogRecord) -> str:
        entry: dict[str, Any] = {
            "ts": datetime.fromtimestamp(record.created, UTC).isoformat(timespec="milliseconds"),
            "level": record.levelname,
            "logger_name": record.name,
            "message": record.getMessage(),
            "app": self.app,
        }
        correlation_id = getattr(record, "correlation_id", None)
        if correlation_id:
            entry["correlationId"] = correlation_id
        if record.exc_info:
            entry["stack_trace"] = self.formatException(record.exc_info)
        return json.dumps(entry, ensure_ascii=False)


def configure(app: str, level: str = "INFO") -> None:
    handler = logging.StreamHandler()
    handler.setFormatter(JsonFormatter(app))
    root = logging.getLogger()
    root.handlers[:] = [handler]
    root.setLevel(level)
    # httpx ghi URL mỗi yêu cầu ở mức INFO; URI ảnh chụp của vài hãng chứa tài khoản nên chỉ giữ cảnh báo
    for noisy in ("httpx", "httpcore", "aiormq", "aio_pika"):
        logging.getLogger(noisy).setLevel(logging.WARNING)
