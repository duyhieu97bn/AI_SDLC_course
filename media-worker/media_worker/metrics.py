from prometheus_client import Counter, Histogram

# result: completed | failed | invalid | duplicate
EXPORT_JOBS = Counter("camhub_export_jobs_total", "Số công việc xuất clip đã xử lý", ["result"])
EXPORT_DURATION = Histogram(
    "camhub_export_job_duration_seconds",
    "Thời gian xử lý một công việc xuất clip (tải, FFmpeg, tải lên)",
    buckets=(1, 2.5, 5, 10, 30, 60, 120, 300, 600, 1200),
)

# Chụp ảnh sự kiện (US-36.3) – result: created | failed | invalid | duplicate
SNAPSHOT_JOBS = Counter("camhub_snapshot_jobs_total", "Số công việc chụp ảnh sự kiện đã xử lý", ["result"])
SNAPSHOT_DURATION = Histogram(
    "camhub_snapshot_job_duration_seconds",
    "Thời gian xử lý một công việc chụp ảnh (lấy khung hình RTSP, tải lên)",
    buckets=(0.5, 1, 2, 3, 5, 8, 12, 20, 30),
)
