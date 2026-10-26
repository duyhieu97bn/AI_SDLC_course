from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Cấu hình đọc từ biến môi trường tiền tố ``CAMHUB_GW_``.

    Bí mật (``rabbitmq_url``, ``internal_token``) có thể đặt bằng file Docker secret
    ``/run/secrets/camhub_gw_rabbitmq_url``, ``/run/secrets/camhub_gw_internal_token``.
    """

    model_config = SettingsConfigDict(env_prefix="CAMHUB_GW_", secrets_dir="/run/secrets")

    rabbitmq_url: str = "amqp://camhub:camhub@localhost:5672/camhub"
    rabbitmq_exchange: str = "device.events"
    # Token Core gửi kèm mỗi lệnh (header X-Internal-Token); để trống thì chỉ dùng trong mạng nội bộ dev
    internal_token: str = ""
    log_level: str = "INFO"

    # Gọi thiết bị
    device_timeout_seconds: float = 10.0
    # Camera HTTPS thường dùng chứng chỉ tự ký nên mặc định không kiểm tra
    device_tls_verify: bool = False
    # Thay host:port trong XAddr thiết bị trả về bằng địa chỉ Core gửi (camera sau NAT/chuyển cổng)
    rewrite_xaddr: bool = True
    # Thời gian nhớ độ lệch đồng hồ và danh sách dịch vụ của mỗi thiết bị
    session_cache_seconds: float = 300.0

    # WS-Discovery
    discovery_port: int = 3702
    discovery_multicast: bool = True

    # Sự kiện (PullPoint)
    events_pull_timeout_seconds: int = 10
    events_subscription_ttl_seconds: int = 60
    events_backoff_max_seconds: float = 60.0

    # Theo dõi online/offline (US-04.1)
    monitor_timeout_seconds: float = 5.0
    monitor_concurrency: int = 50
    monitor_failure_threshold: int = 2

    # Đàm thoại (US-15): hạn nối WebSocket sau khi mở phiên, độ dài tối đa một phiên, thời gian giữ kết quả
    talk_ready_timeout_seconds: float = 30.0
    talk_max_seconds: float = 300.0
    talk_retain_seconds: float = 600.0


settings = Settings()
