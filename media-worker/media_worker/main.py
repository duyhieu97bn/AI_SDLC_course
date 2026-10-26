import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI, Response
from fastapi.responses import PlainTextResponse
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest

from media_worker import __version__
from media_worker.consumer import ExportProcessor, JobConsumer, SnapshotProcessor, Subscription
from media_worker.export_job import ExportOptions, ExportRequest, ExportResult, MinioStore, PlaybackFetcher, run_export
from media_worker.ffmpeg import ffmpeg_version
from media_worker.settings import Settings, settings
from media_worker.snapshot_job import LiveGrabber, SnapshotOptions, SnapshotRequest, SnapshotResult, run_snapshot

logging.basicConfig(level=settings.log_level.upper())
log = logging.getLogger(__name__)

consumer: JobConsumer | None = None


def build_consumer(s: Settings) -> JobConsumer:
    """Ghép các thành phần, nhận việc từ RabbitMQ.

    - Xuất clip: tải từ MediaMTX playback → FFmpeg → MinIO.
    - Chụp ảnh sự kiện: một khung hình từ RTSP MediaMTX → JPEG → MinIO.
    """
    opts = ExportOptions(
        ffmpeg=s.ffmpeg_binary, ffprobe=s.ffprobe_binary, ffmpeg_timeout_seconds=s.ffmpeg_timeout_seconds
    )
    snap_opts = SnapshotOptions(
        ffmpeg=s.ffmpeg_binary,
        timeout_seconds=s.snapshot_timeout_seconds,
        max_width=s.snapshot_max_width,
        jpeg_quality=s.snapshot_jpeg_quality,
    )
    state: dict[str, tuple[PlaybackFetcher, LiveGrabber, MinioStore]] = {}

    def deps() -> tuple[PlaybackFetcher, LiveGrabber, MinioStore]:
        # Đọc secret khi có việc đầu tiên, để worker vẫn khởi động (và /health chạy) khi thiếu secret
        if "deps" not in state:
            secret = s.resolved_media_internal_secret()
            fetcher = PlaybackFetcher(s.playback_url, s.playback_user, secret, s.fetch_timeout_seconds)
            grabber = LiveGrabber(s.rtsp_url, s.playback_user, secret, snap_opts)
            store = MinioStore(s.minio_endpoint, s.minio_access_key, s.resolved_minio_secret_key(), s.minio_secure)
            state["deps"] = (fetcher, grabber, store)
        return state["deps"]

    async def handle_export(req: ExportRequest) -> ExportResult:
        fetcher, _, store = deps()
        return await run_export(req, fetcher, store, opts)

    async def handle_snapshot(req: SnapshotRequest) -> SnapshotResult:
        _, grabber, store = deps()
        return await run_snapshot(req, grabber, store, snap_opts)

    subs = [Subscription(s.export_queue, s.export_routing_key, s.prefetch, ExportProcessor(handle_export))]
    if s.snapshot_enabled:
        subs.append(
            Subscription(
                s.snapshot_queue, s.snapshot_routing_key, s.snapshot_prefetch, SnapshotProcessor(handle_snapshot)
            )
        )
    return JobConsumer(s, subs)


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncIterator[None]:
    global consumer
    if settings.consumer_enabled:
        consumer = build_consumer(settings)
        consumer.start()
    try:
        yield
    finally:
        if consumer is not None:
            await consumer.stop()
            consumer = None


app = FastAPI(title="CamHub Media Worker", version=__version__, lifespan=lifespan)


def rabbit_status() -> str:
    if consumer is None:
        return "DISABLED"
    return "UP" if consumer.connected else "DOWN"


@app.get("/health")
def health(response: Response) -> dict[str, str | None]:
    """DOWN nếu thiếu FFmpeg, vì mọi công việc của worker đều cần FFmpeg.

    Trạng thái RabbitMQ chỉ để quan sát: worker vẫn UP khi RabbitMQ mất vì kết nối tự phục hồi.
    """
    version = ffmpeg_version()
    if version is None:
        response.status_code = 503
        return {"status": "DOWN", "ffmpeg": None, "rabbitmq": rabbit_status()}
    return {"status": "UP", "ffmpeg": version, "version": __version__, "rabbitmq": rabbit_status()}


@app.get("/metrics", response_class=PlainTextResponse)
def metrics() -> PlainTextResponse:
    return PlainTextResponse(generate_latest(), media_type=CONTENT_TYPE_LATEST)
