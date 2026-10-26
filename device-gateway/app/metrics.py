"""Chỉ số Prometheus của Gateway (TS-07)."""

from prometheus_client import Counter, Gauge, Histogram

DEVICE_CALLS = Counter(
    "camhub_gateway_device_calls_total",
    "Số lời gọi ONVIF tới thiết bị, theo thao tác và kết quả (OK hoặc mã lỗi)",
    ["op", "result"],
)
DEVICE_CALL_SECONDS = Histogram(
    "camhub_gateway_device_call_seconds",
    "Thời gian mỗi lời gọi ONVIF",
    ["op"],
    buckets=(0.05, 0.1, 0.25, 0.5, 1, 2, 5, 10, 30),
)
ACTIVE_SUBSCRIPTIONS = Gauge(
    "camhub_gateway_event_subscriptions_active",
    "Số đăng ký PullPoint đang hoạt động (trạng thái ACTIVE)",
)
EVENTS_PUBLISHED = Counter(
    "camhub_gateway_events_published_total",
    "Số thông điệp đã đẩy vào RabbitMQ, theo routing key",
    ["routing_key"],
)
EVENTS_DROPPED = Counter(
    "camhub_gateway_events_dropped_total",
    "Số thông điệp bị bỏ vì hàng đợi chờ RabbitMQ đầy",
)
MONITOR_CAMERAS = Gauge(
    "camhub_gateway_monitor_cameras",
    "Số camera đang được theo dõi, theo trạng thái ONLINE/OFFLINE/UNKNOWN",
    ["status"],
)
