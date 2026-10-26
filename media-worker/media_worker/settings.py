from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Cấu hình đọc từ biến môi trường tiền tố ``CAMHUB_MW_``.

    Bí mật đọc từ file Docker secret (đường dẫn cấu hình được qua các trường ``*_file``):
    ``/run/secrets/rabbitmq_url``, ``/run/secrets/media_internal_secret``, ``/run/secrets/minio_root_password``.
    Đặt trực tiếp ``CAMHUB_MW_RABBITMQ_URL`` (v.v.) sẽ ghi đè nội dung file – chỉ dùng khi chạy dev.
    """

    model_config = SettingsConfigDict(env_prefix="CAMHUB_MW_")

    log_level: str = "INFO"

    # RabbitMQ (SADD 3.7.3): nhận ExportRequested/SnapshotRequested từ ``jobs``, trả kết quả về ``job.results``
    consumer_enabled: bool = True
    rabbitmq_url: str = ""
    rabbitmq_url_file: str = "/run/secrets/rabbitmq_url"
    jobs_exchange: str = "jobs"
    results_exchange: str = "job.results"
    export_queue: str = "media-worker.export"
    export_routing_key: str = "export.requested"
    prefetch: int = 2
    connect_backoff_max_seconds: float = 30.0

    # Chụp ảnh sự kiện (US-36.3): nhận SnapshotRequested, lấy một khung hình từ luồng trực tiếp RTSP của MediaMTX
    snapshot_enabled: bool = True
    snapshot_queue: str = "media-worker.snapshot"
    snapshot_routing_key: str = "snapshot.requested"
    snapshot_prefetch: int = 4
    rtsp_url: str = "rtsp://mediamtx:8554"
    snapshot_timeout_seconds: float = 15.0
    snapshot_max_width: int = 1280
    snapshot_jpeg_quality: int = 3

    # MediaMTX playback server (xuất clip) và RTSP (chụp ảnh): dùng chung tài khoản nội bộ
    playback_url: str = "http://mediamtx:9996"
    playback_user: str = "camhub-internal"
    media_internal_secret: str = ""
    media_internal_secret_file: str = "/run/secrets/media_internal_secret"  # noqa: S105 - đường dẫn file secret
    fetch_timeout_seconds: float = 600.0

    # FFmpeg
    ffmpeg_binary: str = "ffmpeg"
    ffprobe_binary: str = "ffprobe"
    ffmpeg_timeout_seconds: float = 900.0

    # MinIO (S3)
    minio_endpoint: str = "minio:9000"
    minio_secure: bool = False
    minio_access_key: str = "camhub"
    minio_secret_key: str = ""
    minio_secret_key_file: str = "/run/secrets/minio_root_password"  # noqa: S105 - đường dẫn file secret

    def secret(self, value: str, file: str) -> str:
        """Trả về ``value`` nếu đã đặt, ngược lại đọc file secret (bỏ khoảng trắng cuối)."""
        if value:
            return value
        return Path(file).read_text(encoding="utf-8").strip()

    def resolved_rabbitmq_url(self) -> str:
        return self.secret(self.rabbitmq_url, self.rabbitmq_url_file)

    def resolved_media_internal_secret(self) -> str:
        return self.secret(self.media_internal_secret, self.media_internal_secret_file)

    def resolved_minio_secret_key(self) -> str:
        return self.secret(self.minio_secret_key, self.minio_secret_key_file)


settings = Settings()
