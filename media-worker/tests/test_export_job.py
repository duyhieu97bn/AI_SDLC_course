import asyncio
import hashlib
import json
import shutil
import subprocess  # noqa: S404 - tạo video thử bằng ffmpeg, tham số cố định
from pathlib import Path
from typing import Any

import httpx
import pytest
from pydantic import ValidationError

from media_worker.export_job import (
    ExportError,
    ExportOptions,
    ExportRequest,
    PlaybackFetcher,
    build_ffmpeg_args,
    build_playback_url,
    loose_ids,
    parse_request,
    probe,
    run_export,
)

EXPORT_ID = "7f1c3a52-5b8e-4c1e-9a43-2f6f0a7d9b11"
ORG_ID = "0b6c1e2d-3f4a-4b5c-8d6e-7f8091a2b3c4"
CAMERA_ID = "c2d3e4f5-a6b7-4c8d-9e0f-1a2b3c4d5e6f"
MEDIA_PATH = "cam-" + "ab" * 16

HAS_FFMPEG = shutil.which("ffmpeg") is not None and shutil.which("ffprobe") is not None
needs_ffmpeg = pytest.mark.skipif(not HAS_FFMPEG, reason="cần ffmpeg/ffprobe")


def payload(**overrides: Any) -> dict[str, Any]:
    data: dict[str, Any] = {
        "exportId": EXPORT_ID,
        "organizationId": ORG_ID,
        "cameraId": CAMERA_ID,
        "mediaPath": MEDIA_PATH,
        "start": "2026-10-24T06:58:00Z",
        "durationSeconds": 60,
        "bucket": "camhub-exports",
        "objectKey": f"{ORG_ID}/{EXPORT_ID}.mp4",
    }
    data.update(overrides)
    return data


def request(**overrides: Any) -> ExportRequest:
    return parse_request(json.dumps(payload(**overrides)).encode())


# ---------------------------------------------------------------- Payload


def test_parse_valid_payload() -> None:
    req = request()
    assert str(req.export_id) == EXPORT_ID
    assert req.media_path == MEDIA_PATH
    assert req.duration_seconds == 60
    assert req.object_key.endswith(".mp4")


@pytest.mark.parametrize(
    "overrides",
    [
        {"durationSeconds": 0},
        {"durationSeconds": 3601},
        {"mediaPath": "cam-xyz"},
        {"mediaPath": "../etc/passwd"},
        {"start": "2026-10-24T06:58:00"},  # thiếu múi giờ
        {"objectKey": "../other/x.mp4"},
        {"objectKey": "/abs.mp4"},
        {"exportId": "not-a-uuid"},
        {"bucket": "Bad_Bucket"},
    ],
)
def test_parse_rejects_invalid(overrides: dict[str, Any]) -> None:
    with pytest.raises(ValidationError):
        request(**overrides)


def test_parse_rejects_missing_field() -> None:
    data = payload()
    del data["cameraId"]
    with pytest.raises(ValidationError):
        parse_request(json.dumps(data).encode())


def test_loose_ids() -> None:
    assert loose_ids(json.dumps(payload(durationSeconds=0)).encode()) == (EXPORT_ID, ORG_ID)
    assert loose_ids(b"not json") is None
    assert loose_ids(b'{"exportId": "x"}') is None


# ---------------------------------------------------------------- URL playback


def test_playback_url_encodes_start() -> None:
    url = build_playback_url("http://mediamtx:9996/", request(start="2026-10-24T13:58:00+07:00"))
    assert url == (f"http://mediamtx:9996/get?path={MEDIA_PATH}&start=2026-10-24T06%3A58%3A00Z&duration=60&format=mp4")


# ---------------------------------------------------------------- Tham số FFmpeg


def test_ffmpeg_args_copy_h264() -> None:
    args = build_ffmpeg_args(Path("in.mp4"), Path("out.mp4"), "h264")
    assert args[0] == "ffmpeg"
    i = args.index("-c:v")
    assert args[i + 1] == "copy"
    assert "libx264" not in args
    assert args[args.index("-c:a") + 1] == "aac"
    assert args[args.index("-movflags") + 1] == "+faststart"
    assert args[args.index("-map") : args.index("-map") + 4] == ["-map", "0:v:0", "-map", "0:a?"]
    assert args[args.index("-i") + 1] == "in.mp4"
    assert args[-1] == "out.mp4"


@pytest.mark.parametrize("codec", ["hevc", "mpeg4", None])
def test_ffmpeg_args_transcode_other_codecs(codec: str | None) -> None:
    args = build_ffmpeg_args(Path("in.mp4"), Path("out.mp4"), codec, ffmpeg="/usr/bin/ffmpeg")
    assert args[0] == "/usr/bin/ffmpeg"
    i = args.index("-c:v")
    assert args[i + 1 : i + 7] == ["libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt"]


# ---------------------------------------------------------------- Tải từ MediaMTX


def test_fetcher_streams_with_basic_auth(tmp_path: Path) -> None:
    seen: list[httpx.Request] = []

    def handler(req: httpx.Request) -> httpx.Response:
        seen.append(req)
        return httpx.Response(200, content=b"x" * 5000)

    fetcher = PlaybackFetcher("http://mediamtx:9996", "camhub-internal", "s3cret", 5, httpx.MockTransport(handler))
    dest = tmp_path / "src.mp4"
    asyncio.run(fetcher(request(), dest))
    assert dest.read_bytes() == b"x" * 5000
    assert seen[0].url.path == "/get"
    assert seen[0].url.params["start"] == "2026-10-24T06:58:00Z"
    assert seen[0].headers["authorization"] == httpx.BasicAuth("camhub-internal", "s3cret")._auth_header


@pytest.mark.parametrize(
    ("status", "body", "code"),
    [
        (404, b"no recordings found", "NO_RECORDING"),
        (200, b"", "NO_RECORDING"),
        (401, b"", "INTERNAL"),
        (500, b"x", "INTERNAL"),
    ],
)
def test_fetcher_errors(tmp_path: Path, status: int, body: bytes, code: str) -> None:
    transport = httpx.MockTransport(lambda _: httpx.Response(status, content=body))
    fetcher = PlaybackFetcher("http://mediamtx:9996", "u", "p", 5, transport)
    with pytest.raises(ExportError) as ei:
        asyncio.run(fetcher(request(), tmp_path / "src.mp4"))
    assert ei.value.code == code


def test_fetcher_connection_error(tmp_path: Path) -> None:
    def handler(req: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("refused", request=req)

    fetcher = PlaybackFetcher("http://mediamtx:9996", "u", "p", 5, httpx.MockTransport(handler))
    with pytest.raises(ExportError) as ei:
        asyncio.run(fetcher(request(), tmp_path / "src.mp4"))
    assert ei.value.code == "INTERNAL"


# ---------------------------------------------------------------- Công việc đầy đủ (FFmpeg thật, HTTP/MinIO giả)


class FakeStore:
    def __init__(self, fail: bool = False) -> None:
        self.objects: dict[tuple[str, str], tuple[bytes, str]] = {}
        self.fail = fail

    def put_file(self, bucket: str, key: str, path: Path, content_type: str) -> None:
        if self.fail:
            raise ConnectionError("minio down")
        self.objects[(bucket, key)] = (path.read_bytes(), content_type)


def make_source(path: Path, vcodec: str, audio: bool = True) -> None:
    args = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc=size=160x120:rate=10"]
    if audio:
        args += ["-f", "lavfi", "-i", "sine=frequency=440:sample_rate=8000"]
    args += ["-t", "2", "-c:v", vcodec]
    if audio:
        args += ["-c:a", "aac"]
    args += ["-f", "mp4", str(path)]
    subprocess.run(args, check=True, timeout=60)  # noqa: S603


def file_fetcher(src: Path) -> Any:
    async def fetch(req: ExportRequest, dest: Path) -> None:
        shutil.copyfile(src, dest)

    return fetch


@needs_ffmpeg
@pytest.mark.parametrize(("vcodec", "audio"), [("libx264", True), ("mpeg4", True), ("libx264", False)])
def test_run_export_produces_h264_mp4(tmp_path: Path, vcodec: str, audio: bool) -> None:
    src = tmp_path / "fixture.mp4"
    make_source(src, vcodec, audio)
    store = FakeStore()
    req = request()

    result = asyncio.run(run_export(req, file_fetcher(src), store, ExportOptions(work_dir=str(tmp_path))))

    data, content_type = store.objects[("camhub-exports", req.object_key)]
    assert content_type == "video/mp4"
    assert result.size_bytes == len(data) > 0
    assert result.sha256 == hashlib.sha256(data).hexdigest()
    assert 1.5 <= result.duration_seconds <= 2.5

    out = tmp_path / "out.mp4"
    out.write_bytes(data)
    codec, _ = asyncio.run(probe(out, "ffprobe", 30))
    assert codec == "h264"
    # +faststart: moov đứng trước mdat
    assert data.find(b"moov") < data.find(b"mdat")
    # thư mục tạm đã được xóa
    assert not any(p.name.startswith("export-") for p in tmp_path.iterdir())


@needs_ffmpeg
def test_run_export_garbage_source_is_ffmpeg_failed(tmp_path: Path) -> None:
    src = tmp_path / "garbage.mp4"
    src.write_bytes(b"not a video" * 100)
    with pytest.raises(ExportError) as ei:
        asyncio.run(run_export(request(), file_fetcher(src), FakeStore(), ExportOptions()))
    assert ei.value.code == "FFMPEG_FAILED"


@needs_ffmpeg
def test_run_export_upload_failure(tmp_path: Path) -> None:
    src = tmp_path / "fixture.mp4"
    make_source(src, "libx264", audio=False)
    with pytest.raises(ExportError) as ei:
        asyncio.run(run_export(request(), file_fetcher(src), FakeStore(fail=True), ExportOptions()))
    assert ei.value.code == "UPLOAD_FAILED"


def test_run_export_propagates_fetch_error() -> None:
    async def fetch(req: ExportRequest, dest: Path) -> None:
        raise ExportError("NO_RECORDING", "none")

    with pytest.raises(ExportError) as ei:
        asyncio.run(run_export(request(), fetch, FakeStore(), ExportOptions()))
    assert ei.value.code == "NO_RECORDING"
