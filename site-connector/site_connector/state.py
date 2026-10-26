"""Lưu thông tin đăng nhập sau khi kích hoạt (credentials.json, quyền 0600).

Tệp chứa connectorId/secret (Basic auth với Core) và tài khoản đường hầm. Khi Core thu hồi bộ kết nối, thông tin
đăng nhập bị xóa và chỉ giữ dấu "revoked" kèm SHA-256 của mã kích hoạt đã dùng, để lần chạy sau không thử lại
mã cũ mà báo rõ cần mã mới.
"""

from __future__ import annotations

import contextlib
import hashlib
import json
import os
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any


@dataclass(frozen=True)
class Credentials:
    connector_id: str
    secret: str
    tunnel_url: str
    tunnel_user: str
    tunnel_password: str
    activation_code_sha256: str = ""


@dataclass(frozen=True)
class StoredState:
    credentials: Credentials | None
    # Mã kích hoạt (dạng hash) của lần kích hoạt đã bị thu hồi; rỗng nếu chưa bị thu hồi
    revoked_code_sha256: str = ""


def code_hash(code: str) -> str:
    return hashlib.sha256(code.strip().encode()).hexdigest()


def _write_private(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with contextlib.suppress(OSError):
        path.parent.chmod(0o700)
    tmp = path.with_suffix(".tmp")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)
    os.replace(tmp, path)
    with contextlib.suppress(OSError):
        path.chmod(0o600)


def load(path: Path) -> StoredState:
    """Đọc trạng thái; tệp không có thì trả trạng thái rỗng. Tệp hỏng thì ``ValueError``."""
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return StoredState(credentials=None)
    except (OSError, json.JSONDecodeError) as e:
        raise ValueError(f"Không đọc được {path}: {e}") from e
    creds = raw.get("credentials")
    try:
        credentials = Credentials(**creds) if creds else None
    except TypeError as e:
        raise ValueError(f"Tệp {path} sai định dạng: {e}") from e
    return StoredState(credentials=credentials, revoked_code_sha256=str(raw.get("revokedCodeSha256") or ""))


def save_credentials(path: Path, credentials: Credentials) -> None:
    _write_private(path, {"credentials": asdict(credentials)})


def mark_revoked(path: Path, credentials: Credentials) -> None:
    """Xóa thông tin đăng nhập (đã vô hiệu), chỉ giữ hash của mã kích hoạt cũ."""
    _write_private(path, {"credentials": None, "revokedCodeSha256": credentials.activation_code_sha256})
