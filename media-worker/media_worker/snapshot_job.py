"""Chụp ảnh sự kiện (US-36.3, SADD 3.3).

Luồng một công việc: đọc MỘT khung hình từ luồng trực tiếp qua RTSP của MediaMTX (tài khoản nội bộ) → FFmpeg
mã hóa JPEG (thu nhỏ về tối đa 1280 px chiều ngang, giữ tỉ lệ) → tải lên MinIO → trả ``SnapshotResult``.
Path MediaMTX chạy theo yêu cầu: người đọc đầu tiên mới kích hoạt kéo luồng từ camera nên phải chờ đủ lâu.
Không dùng bản ghi làm phương án dự phòng: luồng trực tiếp lỗi thì báo ``STREAM_UNAVAILABLE``.
"""

from __future__ import annotations

import asyncio
import json
import logging
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Literal, Protocol
from urllib.parse import quote, urlsplit, urlunsplit
from uuid import UUID

from pydantic import AwareDatetime, BaseModel, ConfigDict, Field, field_validator
from pydantic.alias_generators import to_camel

from media_worker.export_job import ObjectStore, run_process

log = logging.getLogger(__name__)

SnapshotErrorCode = Literal["STREAM_UNAVAILABLE", "UPLOAD_FAILED", "INTERNAL"]

JPEG_MAGIC = b"\xff\xd8\xff"


class SnapshotError(Exception):
    """Lỗi chụp ảnh có mã gửi về Core trong ``snapshot.failed``."""

    def __init__(self, code: SnapshotErrorCode, message: str) -> None:
        super().__init__(message)
        self.code: SnapshotErrorCode = code
        self.message = message


class SnapshotRequest(BaseModel):
    """Payload ``snapshot.requested`` trên exchange ``jobs`` (khóa JSON dạng camelCase)."""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, frozen=True)

    snapshot_id: UUID
    organization_id: UUID
    camera_id: UUID
    media_path: str = Field(pattern=r"^cam-[0-9a-f]{32}$")
    at: AwareDatetime
    bucket: str = Field(min_length=3, max_length=63, pattern=r"^[a-z0-9][a-z0-9.-]+[a-z0-9]$")
    object_key: str = Field(min_length=1, max_length=1024)

    @field_validator("object_key")
    @classmethod
    def _safe_key(cls, v: str) -> str:
        if v.startswith("/") or ".." in v.split("/") or "\\" in v:
            raise ValueError("objectKey không hợp lệ")
        return v


def parse_request(body: bytes) -> SnapshotRequest:
    """Đọc payload JSON; ném ``pydantic.ValidationError``/``ValueError`` nếu sai hợp đồng."""
    return SnapshotRequest.model_validate_json(body)


def loose_ids(body: bytes) -> tuple[str, str, str] | None:
    """Lấy (snapshotId, organizationId, cameraId) từ payload sai hợp đồng để vẫn báo ``snapshot.failed``."""
    try:
        data = json.loads(body)
        return (
            str(UUID(str(data["snapshotId"]))),
            str(UUID(str(data["organizationId"]))),
            str(UUID(str(data["cameraId"]))),
        )
    except (ValueError, TypeError, KeyError):
        return None


@dataclass(frozen=True)
class SnapshotResult:
    bucket: str
    object_key: str
    size_bytes: int


def created_payload(req: SnapshotRequest, result: SnapshotResult) -> dict[str, Any]:
    return {
        "snapshotId": str(req.snapshot_id),
        "organizationId": str(req.organization_id),
        "cameraId": str(req.camera_id),
        "bucket": result.bucket,
        "objectKey": result.object_key,
        "sizeBytes": result.size_bytes,
    }


def failed_payload(
    snapshot_id: str, organization_id: str, camera_id: str, code: SnapshotErrorCode, message: str
) -> dict[str, Any]:
    return {
        "snapshotId": snapshot_id,
        "organizationId": organization_id,
        "cameraId": camera_id,
        "code": code,
        "error": message[:500],
    }


# ---------------------------------------------------------------- FFmpeg


def build_rtsp_url(base_url: str, user: str, password: str, media_path: str) -> str:
    """``rtsp://<user>:<mật khẩu>@mediamtx:8554/<mediaPath>`` – thông tin đăng nhập được mã hóa phần trăm."""
    parts = urlsplit(base_url.rstrip("/"))
    host = parts.netloc.rsplit("@", 1)[-1]
    netloc = f"{quote(user, safe='')}:{quote(password, safe='')}@{host}"
    return urlunsplit((parts.scheme, netloc, f"{parts.path}/{media_path}", "", ""))


def redact(url: str) -> str:
    """Che mật khẩu trong URL trước khi ghi log."""
    parts = urlsplit(url)
    if parts.password is None:
        return url
    host = parts.netloc.rsplit("@", 1)[-1]
    return urlunsplit((parts.scheme, f"{parts.username}:***@{host}", parts.path, parts.query, parts.fragment))


@dataclass(frozen=True)
class SnapshotOptions:
    ffmpeg: str = "ffmpeg"
    timeout_seconds: float = 15.0
    max_width: int = 1280
    jpeg_quality: int = 3  # -q:v của mjpeg: 2 (đẹp nhất) … 31
    work_dir: str | None = None


def build_grab_args(input_url: str, dst: Path, opts: SnapshotOptions) -> list[str]:
    """Tham số FFmpeg lấy một khung hình JPEG; nguồn RTSP thì ép TCP và đặt timeout socket."""
    args = [opts.ffmpeg, "-hide_banner", "-nostdin", "-loglevel", "error", "-y"]
    if input_url.startswith(("rtsp://", "rtsps://")):
        timeout_us = str(int(opts.timeout_seconds * 1_000_000))
        args += ["-rtsp_transport", "tcp", "-timeout", timeout_us]
    args += [
        "-i",
        input_url,
        "-map",
        "0:v:0",
        "-an",
        "-frames:v",
        "1",
        "-vf",
        f"scale=w='min({opts.max_width},iw)':h=-2",
        "-c:v",
        "mjpeg",
        "-q:v",
        str(opts.jpeg_quality),
        "-f",
        "image2",
        "-update",
        "1",
        str(dst),
    ]
    return args


async def grab_frame(input_url: str, dst: Path, opts: SnapshotOptions) -> None:
    """Lấy một khung hình từ ``input_url`` (RTSP hoặc file cục bộ) ra JPEG ``dst``; lỗi → ``SnapshotError``."""
    args = build_grab_args(input_url, dst, opts)
    try:
        code, _, err = await run_process(args, opts.timeout_seconds)
    except TimeoutError as e:
        raise SnapshotError("STREAM_UNAVAILABLE", "Hết thời gian chờ khung hình từ luồng trực tiếp") from e
    except OSError as e:
        raise SnapshotError("INTERNAL", f"Không chạy được FFmpeg: {e.strerror or e}") from e
    if code != 0 or not dst.exists() or dst.stat().st_size == 0:
        # Chỉ giữ dòng lỗi cuối của FFmpeg (đã che mật khẩu) để Core hiển thị gọn
        lines = [ln for ln in err.replace(input_url, redact(input_url)).splitlines() if ln.strip()]
        detail = lines[-1].strip()[-200:] if lines else "không có khung hình"
        raise SnapshotError("STREAM_UNAVAILABLE", f"Không lấy được khung hình từ luồng trực tiếp: {detail}")
    with dst.open("rb") as f:
        if f.read(3) != JPEG_MAGIC:
            raise SnapshotError("INTERNAL", "FFmpeg không tạo ra ảnh JPEG hợp lệ")


class Grabber(Protocol):
    async def __call__(self, req: SnapshotRequest, dest: Path) -> None: ...


class LiveGrabber:
    """Lấy khung hình từ luồng trực tiếp của camera qua RTSP MediaMTX bằng tài khoản nội bộ."""

    def __init__(self, rtsp_base_url: str, user: str, password: str, opts: SnapshotOptions) -> None:
        self._base = rtsp_base_url
        self._user = user
        self._password = password
        self._opts = opts

    async def __call__(self, req: SnapshotRequest, dest: Path) -> None:
        url = build_rtsp_url(self._base, self._user, self._password, req.media_path)
        await grab_frame(url, dest, self._opts)


# ---------------------------------------------------------------- Công việc


async def run_snapshot(
    req: SnapshotRequest, grabber: Grabber, store: ObjectStore, opts: SnapshotOptions
) -> SnapshotResult:
    """Thực hiện một công việc chụp ảnh; ném ``SnapshotError`` khi thất bại. File tạm luôn được xóa."""
    with tempfile.TemporaryDirectory(prefix="snapshot-", dir=opts.work_dir) as tmp:
        dst = Path(tmp) / "snapshot.jpg"
        await grabber(req, dst)
        size = dst.stat().st_size
        try:
            await asyncio.to_thread(store.put_file, req.bucket, req.object_key, dst, "image/jpeg")
        except Exception as e:  # noqa: BLE001 - mọi lỗi S3/mạng đều quy về UPLOAD_FAILED
            log.warning("Tải %s lên MinIO thất bại: %s: %s", req.object_key, type(e).__name__, e)
            raise SnapshotError("UPLOAD_FAILED", f"Không tải được ảnh lên kho lưu trữ: {type(e).__name__}") from e
        return SnapshotResult(bucket=req.bucket, object_key=req.object_key, size_bytes=size)
