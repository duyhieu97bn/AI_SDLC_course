"""Xuất clip (US-20.2, SADD 3.3).

Luồng một công việc: tải đoạn ghi hình từ MediaMTX playback server về file tạm → FFmpeg ra MP4 H.264
(``-movflags +faststart``) → tải lên MinIO → trả ``ExportResult``. Mọi lỗi nghiệp vụ được báo bằng
``ExportError`` kèm mã để Core hiển thị cho người dùng.
"""

from __future__ import annotations

import asyncio
import contextlib
import hashlib
import json
import logging
import tempfile
from dataclasses import dataclass
from datetime import UTC
from pathlib import Path
from typing import Any, Literal, Protocol
from urllib.parse import urlencode
from uuid import UUID

import httpx
from minio import Minio
from pydantic import AwareDatetime, BaseModel, ConfigDict, Field, field_validator
from pydantic.alias_generators import to_camel

log = logging.getLogger(__name__)

ErrorCode = Literal["NO_RECORDING", "FFMPEG_FAILED", "UPLOAD_FAILED", "INTERNAL"]

MAX_DURATION_SECONDS = 3600


class ExportError(Exception):
    """Lỗi xuất clip có mã gửi về Core trong ``export.failed``."""

    def __init__(self, code: ErrorCode, message: str) -> None:
        super().__init__(message)
        self.code: ErrorCode = code
        self.message = message


class ExportRequest(BaseModel):
    """Payload ``export.requested`` trên exchange ``jobs`` (khóa JSON dạng camelCase)."""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, frozen=True)

    export_id: UUID
    organization_id: UUID
    camera_id: UUID
    media_path: str = Field(pattern=r"^cam-[0-9a-f]{32}$")
    start: AwareDatetime
    duration_seconds: int = Field(gt=0, le=MAX_DURATION_SECONDS)
    bucket: str = Field(min_length=3, max_length=63, pattern=r"^[a-z0-9][a-z0-9.-]+[a-z0-9]$")
    object_key: str = Field(min_length=1, max_length=1024)

    @field_validator("object_key")
    @classmethod
    def _safe_key(cls, v: str) -> str:
        if v.startswith("/") or ".." in v.split("/") or "\\" in v:
            raise ValueError("objectKey không hợp lệ")
        return v


def parse_request(body: bytes) -> ExportRequest:
    """Đọc payload JSON; ném ``pydantic.ValidationError``/``ValueError`` nếu sai hợp đồng."""
    return ExportRequest.model_validate_json(body)


def loose_ids(body: bytes) -> tuple[str, str] | None:
    """Lấy (exportId, organizationId) từ payload sai hợp đồng để vẫn báo ``export.failed`` được cho Core."""
    try:
        data = json.loads(body)
        return str(UUID(str(data["exportId"]))), str(UUID(str(data["organizationId"])))
    except (ValueError, TypeError, KeyError):
        return None


@dataclass(frozen=True)
class ExportResult:
    bucket: str
    object_key: str
    size_bytes: int
    duration_seconds: float
    sha256: str


def completed_payload(req: ExportRequest, result: ExportResult) -> dict[str, Any]:
    return {
        "exportId": str(req.export_id),
        "organizationId": str(req.organization_id),
        "objectKey": result.object_key,
        "bucket": result.bucket,
        "sizeBytes": result.size_bytes,
        "durationSeconds": result.duration_seconds,
        "sha256": result.sha256,
    }


def failed_payload(export_id: str, organization_id: str, code: ErrorCode, message: str) -> dict[str, Any]:
    return {"exportId": export_id, "organizationId": organization_id, "error": message[:500], "code": code}


def progress_payload(req: ExportRequest) -> dict[str, Any]:
    return {"exportId": str(req.export_id), "organizationId": str(req.organization_id), "state": "PROCESSING"}


# ---------------------------------------------------------------- Tải đoạn ghi hình


def format_start(req: ExportRequest) -> str:
    """Thời điểm bắt đầu dạng RFC 3339 theo UTC (``2026-10-24T06:58:00Z``)."""
    return req.start.astimezone(UTC).isoformat().replace("+00:00", "Z")


def build_playback_url(base_url: str, req: ExportRequest) -> str:
    query = urlencode(
        {
            "path": req.media_path,
            "start": format_start(req),
            "duration": str(req.duration_seconds),
            "format": "mp4",
        }
    )
    return f"{base_url.rstrip('/')}/get?{query}"


class Fetcher(Protocol):
    async def __call__(self, req: ExportRequest, dest: Path) -> None: ...


class PlaybackFetcher:
    """Tải đoạn ghi hình từ MediaMTX playback server (HTTP basic auth tài khoản nội bộ) về file."""

    def __init__(
        self,
        base_url: str,
        user: str,
        password: str,
        timeout_seconds: float,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self._base_url = base_url
        self._auth = httpx.BasicAuth(user, password)
        self._timeout = httpx.Timeout(timeout_seconds, connect=10.0)
        self._transport = transport

    async def __call__(self, req: ExportRequest, dest: Path) -> None:
        url = build_playback_url(self._base_url, req)
        try:
            async with (
                httpx.AsyncClient(auth=self._auth, timeout=self._timeout, transport=self._transport) as client,
                client.stream("GET", url) as res,
            ):
                if res.status_code == httpx.codes.NOT_FOUND:
                    raise ExportError("NO_RECORDING", "Không có bản ghi trong khoảng thời gian yêu cầu")
                if res.status_code in (httpx.codes.UNAUTHORIZED, httpx.codes.FORBIDDEN):
                    raise ExportError("INTERNAL", f"MediaMTX từ chối tài khoản nội bộ (HTTP {res.status_code})")
                if res.status_code != httpx.codes.OK:
                    body = (await res.aread())[:200].decode("utf-8", "replace")
                    raise ExportError("INTERNAL", f"MediaMTX playback lỗi HTTP {res.status_code}: {body}")
                with dest.open("wb") as f:
                    async for chunk in res.aiter_bytes(1024 * 1024):
                        f.write(chunk)
        except httpx.TimeoutException as e:
            raise ExportError("INTERNAL", "Hết thời gian tải đoạn ghi hình từ MediaMTX") from e
        except httpx.HTTPError as e:
            raise ExportError("INTERNAL", f"Không tải được đoạn ghi hình: {type(e).__name__}") from e
        if dest.stat().st_size == 0:
            raise ExportError("NO_RECORDING", "Không có bản ghi trong khoảng thời gian yêu cầu")


# ---------------------------------------------------------------- FFmpeg


def build_ffmpeg_args(src: Path, dst: Path, video_codec: str | None, ffmpeg: str = "ffmpeg") -> list[str]:
    """Tham số FFmpeg: giữ nguyên H.264 (``copy``), codec khác thì chuyển sang libx264; âm thanh → AAC nếu có."""
    if video_codec == "h264":
        video = ["-c:v", "copy"]
    else:
        video = ["-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p"]
    return [
        ffmpeg,
        "-hide_banner",
        "-nostdin",
        "-loglevel",
        "error",
        "-y",
        "-i",
        str(src),
        "-map",
        "0:v:0",
        "-map",
        "0:a?",
        *video,
        "-c:a",
        "aac",
        "-b:a",
        "128k",
        "-movflags",
        "+faststart",
        "-f",
        "mp4",
        str(dst),
    ]


async def run_process(args: list[str], timeout: float) -> tuple[int, str, str]:
    """Chạy tiến trình (không qua shell); hết thời gian thì kill và ném ``TimeoutError``."""
    proc = await asyncio.create_subprocess_exec(
        *args, stdin=asyncio.subprocess.DEVNULL, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE
    )
    try:
        out, err = await asyncio.wait_for(proc.communicate(), timeout)
    except TimeoutError:
        with contextlib.suppress(ProcessLookupError):
            proc.kill()
        await proc.wait()
        raise
    return proc.returncode or 0, out.decode("utf-8", "replace"), err.decode("utf-8", "replace")


async def probe(path: Path, ffprobe: str, timeout: float) -> tuple[str | None, float | None]:
    """(codec video đầu tiên, thời lượng giây) của file; ``(None, None)`` nếu ffprobe không đọc được."""
    args = [
        ffprobe,
        "-v",
        "error",
        "-select_streams",
        "v:0",
        "-show_entries",
        "stream=codec_name:format=duration",
        "-of",
        "json",
        str(path),
    ]
    code, out, _ = await run_process(args, timeout)
    if code != 0:
        return None, None
    try:
        data = json.loads(out)
    except ValueError:
        return None, None
    streams = data.get("streams") or []
    codec = streams[0].get("codec_name") if streams else None
    duration: float | None = None
    with contextlib.suppress(TypeError, ValueError):
        duration = float(data.get("format", {}).get("duration"))
    return codec, duration


# ---------------------------------------------------------------- Lưu trữ


class ObjectStore(Protocol):
    def put_file(self, bucket: str, key: str, path: Path, content_type: str) -> None: ...


class MinioStore:
    """Tải file lên MinIO; tạo bucket nếu chưa có. Client ``minio`` là đồng bộ nên gọi qua ``asyncio.to_thread``."""

    def __init__(self, endpoint: str, access_key: str, secret_key: str, secure: bool) -> None:
        self._client = Minio(endpoint, access_key=access_key, secret_key=secret_key, secure=secure)
        self._known_buckets: set[str] = set()

    def put_file(self, bucket: str, key: str, path: Path, content_type: str) -> None:
        if bucket not in self._known_buckets:
            if not self._client.bucket_exists(bucket):
                self._client.make_bucket(bucket)
            self._known_buckets.add(bucket)
        self._client.fput_object(bucket, key, str(path), content_type=content_type)


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


# ---------------------------------------------------------------- Công việc


@dataclass(frozen=True)
class ExportOptions:
    ffmpeg: str = "ffmpeg"
    ffprobe: str = "ffprobe"
    ffmpeg_timeout_seconds: float = 900.0
    work_dir: str | None = None


async def run_export(req: ExportRequest, fetcher: Fetcher, store: ObjectStore, opts: ExportOptions) -> ExportResult:
    """Thực hiện một công việc xuất clip; ném ``ExportError`` khi thất bại. File tạm luôn được xóa."""
    with tempfile.TemporaryDirectory(prefix="export-", dir=opts.work_dir) as tmp:
        src = Path(tmp) / "source.mp4"
        dst = Path(tmp) / "clip.mp4"

        await fetcher(req, src)

        try:
            codec, _ = await probe(src, opts.ffprobe, opts.ffmpeg_timeout_seconds)
            if codec is None:
                raise ExportError("FFMPEG_FAILED", "Đoạn ghi hình không có luồng video đọc được")
            args = build_ffmpeg_args(src, dst, codec, opts.ffmpeg)
            log.info("Xuất %s: codec nguồn %s, %s", req.export_id, codec, "copy" if codec == "h264" else "libx264")
            code, _, err = await run_process(args, opts.ffmpeg_timeout_seconds)
            if code != 0 or not dst.exists() or dst.stat().st_size == 0:
                raise ExportError("FFMPEG_FAILED", f"FFmpeg lỗi (mã {code}): {err.strip()[-300:]}")
            _, duration = await probe(dst, opts.ffprobe, opts.ffmpeg_timeout_seconds)
        except TimeoutError as e:
            raise ExportError("FFMPEG_FAILED", "FFmpeg chạy quá thời gian cho phép") from e
        except OSError as e:
            raise ExportError("FFMPEG_FAILED", f"Không chạy được FFmpeg: {e.strerror or e}") from e

        size = dst.stat().st_size
        digest = await asyncio.to_thread(sha256_of, dst)
        try:
            await asyncio.to_thread(store.put_file, req.bucket, req.object_key, dst, "video/mp4")
        except Exception as e:  # noqa: BLE001 - mọi lỗi S3/mạng đều quy về UPLOAD_FAILED
            log.warning("Tải %s lên MinIO thất bại: %s: %s", req.object_key, type(e).__name__, e)
            raise ExportError("UPLOAD_FAILED", f"Không tải được clip lên kho lưu trữ: {type(e).__name__}") from e

        return ExportResult(
            bucket=req.bucket,
            object_key=req.object_key,
            size_bytes=size,
            duration_seconds=round(duration if duration is not None else float(req.duration_seconds), 3),
            sha256=digest,
        )
