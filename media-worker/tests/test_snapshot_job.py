import asyncio
import json
import shutil
import subprocess  # noqa: S404 - tạo video thử bằng ffmpeg, tham số cố định
from pathlib import Path
from typing import Any

import pytest
from pydantic import ValidationError

from media_worker.snapshot_job import (
    JPEG_MAGIC,
    SnapshotError,
    SnapshotOptions,
    SnapshotRequest,
    SnapshotResult,
    build_grab_args,
    build_rtsp_url,
    created_payload,
    grab_frame,
    loose_ids,
    parse_request,
    redact,
    run_snapshot,
)

SNAPSHOT_ID = "5a1d2c3b-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
ORG_ID = "0b6c1e2d-3f4a-4b5c-8d6e-7f8091a2b3c4"
CAMERA_ID = "c2d3e4f5-a6b7-4c8d-9e0f-1a2b3c4d5e6f"
MEDIA_PATH = "cam-" + "ab" * 16

HAS_FFMPEG = shutil.which("ffmpeg") is not None and shutil.which("ffprobe") is not None
needs_ffmpeg = pytest.mark.skipif(not HAS_FFMPEG, reason="cần ffmpeg/ffprobe")


def payload(**overrides: Any) -> dict[str, Any]:
    data: dict[str, Any] = {
        "snapshotId": SNAPSHOT_ID,
        "organizationId": ORG_ID,
        "cameraId": CAMERA_ID,
        "mediaPath": MEDIA_PATH,
        "at": "2026-10-25T03:15:00Z",
        "bucket": "camhub-snapshots",
        "objectKey": f"{ORG_ID}/{CAMERA_ID}/{SNAPSHOT_ID}.jpg",
    }
    data.update(overrides)
    return data


def request(**overrides: Any) -> SnapshotRequest:
    return parse_request(json.dumps(payload(**overrides)).encode())


# ---------------------------------------------------------------- Payload


def test_parse_valid_payload() -> None:
    req = request()
    assert str(req.snapshot_id) == SNAPSHOT_ID
    assert req.media_path == MEDIA_PATH
    assert req.bucket == "camhub-snapshots"


@pytest.mark.parametrize(
    "overrides",
    [
        {"mediaPath": "cam-xyz"},
        {"mediaPath": "../etc/passwd"},
        {"at": "2026-10-25T03:15:00"},  # thiếu múi giờ
        {"objectKey": "../x.jpg"},
        {"objectKey": "/abs.jpg"},
        {"snapshotId": "not-a-uuid"},
        {"bucket": "Bad_Bucket"},
    ],
)
def test_parse_rejects_invalid(overrides: dict[str, Any]) -> None:
    with pytest.raises(ValidationError):
        request(**overrides)


def test_loose_ids() -> None:
    assert loose_ids(json.dumps(payload(mediaPath="bad")).encode()) == (SNAPSHOT_ID, ORG_ID, CAMERA_ID)
    assert loose_ids(b"not json") is None
    assert loose_ids(json.dumps({"snapshotId": SNAPSHOT_ID, "organizationId": ORG_ID}).encode()) is None


def test_created_payload() -> None:
    req = request()
    assert created_payload(req, SnapshotResult(req.bucket, req.object_key, 4321)) == {
        "snapshotId": SNAPSHOT_ID,
        "organizationId": ORG_ID,
        "cameraId": CAMERA_ID,
        "bucket": "camhub-snapshots",
        "objectKey": req.object_key,
        "sizeBytes": 4321,
    }


# ---------------------------------------------------------------- URL và tham số FFmpeg


def test_rtsp_url_encodes_credentials() -> None:
    url = build_rtsp_url("rtsp://mediamtx:8554/", "camhub-internal", "p@ss:/w", MEDIA_PATH)
    assert url == f"rtsp://camhub-internal:p%40ss%3A%2Fw@mediamtx:8554/{MEDIA_PATH}"
    assert redact(url) == f"rtsp://camhub-internal:***@mediamtx:8554/{MEDIA_PATH}"
    assert redact("videos/x.mp4") == "videos/x.mp4"


def test_grab_args_rtsp() -> None:
    args = build_grab_args("rtsp://u:p@mediamtx:8554/cam-x", Path("out.jpg"), SnapshotOptions(timeout_seconds=15))
    assert args[args.index("-rtsp_transport") + 1] == "tcp"
    assert args[args.index("-timeout") + 1] == "15000000"
    assert args.index("-rtsp_transport") < args.index("-i")
    assert args[args.index("-frames:v") + 1] == "1"
    assert args[args.index("-q:v") + 1] == "3"
    assert args[args.index("-vf") + 1] == "scale=w='min(1280,iw)':h=-2"
    assert args[-1] == "out.jpg"


def test_grab_args_local_file_has_no_rtsp_options() -> None:
    args = build_grab_args("videos/in.mp4", Path("out.jpg"), SnapshotOptions())
    assert "-rtsp_transport" not in args
    assert "-timeout" not in args


# ---------------------------------------------------------------- FFmpeg thật (nguồn là file cục bộ)


def make_source(path: Path, size: str) -> None:
    args = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", f"testsrc=size={size}:rate=10"]
    args += ["-t", "1", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-f", "mp4", str(path)]
    subprocess.run(args, check=True, timeout=60)  # noqa: S603


def jpeg_size(path: Path) -> tuple[int, int]:
    out = subprocess.run(  # noqa: S603
        ["ffprobe", "-v", "error", "-show_entries", "stream=width,height,codec_name", "-of", "json", str(path)],  # noqa: S607
        capture_output=True,
        text=True,
        check=True,
        timeout=30,
    )
    stream = json.loads(out.stdout)["streams"][0]
    assert stream["codec_name"] == "mjpeg"
    return int(stream["width"]), int(stream["height"])


@needs_ffmpeg
@pytest.mark.parametrize(("size", "expected"), [("1920x1080", (1280, 720)), ("640x360", (640, 360))])
def test_grab_frame_scales_down_keeping_aspect(tmp_path: Path, size: str, expected: tuple[int, int]) -> None:
    src = tmp_path / "src.mp4"
    make_source(src, size)
    dst = tmp_path / "out.jpg"
    asyncio.run(grab_frame(str(src), dst, SnapshotOptions()))
    assert dst.read_bytes()[:3] == JPEG_MAGIC
    assert jpeg_size(dst) == expected


@needs_ffmpeg
def test_grab_frame_unreadable_source_is_stream_unavailable(tmp_path: Path) -> None:
    src = tmp_path / "garbage.mp4"
    src.write_bytes(b"not a video" * 100)
    with pytest.raises(SnapshotError) as ei:
        asyncio.run(grab_frame(str(src), tmp_path / "out.jpg", SnapshotOptions()))
    assert ei.value.code == "STREAM_UNAVAILABLE"


@needs_ffmpeg
def test_grab_frame_refused_rtsp_hides_password(tmp_path: Path) -> None:
    url = "rtsp://camhub-internal:topsecret@127.0.0.1:1/cam-x"
    with pytest.raises(SnapshotError) as ei:
        asyncio.run(grab_frame(url, tmp_path / "out.jpg", SnapshotOptions(timeout_seconds=5)))
    assert ei.value.code == "STREAM_UNAVAILABLE"
    assert "topsecret" not in ei.value.message


def test_grab_frame_missing_ffmpeg_is_internal(tmp_path: Path) -> None:
    with pytest.raises(SnapshotError) as ei:
        asyncio.run(grab_frame("/x.mp4", tmp_path / "o.jpg", SnapshotOptions(ffmpeg="/khong/co/ffmpeg")))
    assert ei.value.code == "INTERNAL"


# ---------------------------------------------------------------- Công việc đầy đủ (FFmpeg thật, MinIO giả)


class FakeStore:
    def __init__(self, fail: bool = False) -> None:
        self.objects: dict[tuple[str, str], tuple[bytes, str]] = {}
        self.fail = fail

    def put_file(self, bucket: str, key: str, path: Path, content_type: str) -> None:
        if self.fail:
            raise ConnectionError("minio down")
        self.objects[(bucket, key)] = (path.read_bytes(), content_type)


def file_grabber(src: Path, opts: SnapshotOptions) -> Any:
    async def grab(req: SnapshotRequest, dest: Path) -> None:
        await grab_frame(str(src), dest, opts)

    return grab


@needs_ffmpeg
def test_run_snapshot_uploads_jpeg(tmp_path: Path) -> None:
    src = tmp_path / "src.mp4"
    make_source(src, "320x240")
    opts = SnapshotOptions(work_dir=str(tmp_path))
    store = FakeStore()
    req = request()

    result = asyncio.run(run_snapshot(req, file_grabber(src, opts), store, opts))

    data, content_type = store.objects[("camhub-snapshots", req.object_key)]
    assert content_type == "image/jpeg"
    assert data[:3] == JPEG_MAGIC
    assert result.size_bytes == len(data)
    assert result.object_key == req.object_key
    assert not any(p.name.startswith("snapshot-") for p in tmp_path.iterdir())


@needs_ffmpeg
def test_run_snapshot_upload_failure(tmp_path: Path) -> None:
    src = tmp_path / "src.mp4"
    make_source(src, "320x240")
    opts = SnapshotOptions()
    with pytest.raises(SnapshotError) as ei:
        asyncio.run(run_snapshot(request(), file_grabber(src, opts), FakeStore(fail=True), opts))
    assert ei.value.code == "UPLOAD_FAILED"


def test_run_snapshot_propagates_grab_error() -> None:
    async def grab(req: SnapshotRequest, dest: Path) -> None:
        raise SnapshotError("STREAM_UNAVAILABLE", "camera offline")

    store = FakeStore()
    with pytest.raises(SnapshotError) as ei:
        asyncio.run(run_snapshot(request(), grab, store, SnapshotOptions()))
    assert ei.value.code == "STREAM_UNAVAILABLE"
    assert store.objects == {}
