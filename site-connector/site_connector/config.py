from pathlib import Path

from pydantic import field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

# Tệp cấu hình mặc định (dạng KEY=VALUE như .env); không có thì chỉ đọc biến môi trường
DEFAULT_CONFIG_FILE = "/etc/camhub-connector/connector.env"


class ConnectorConfig(BaseSettings):
    """Cấu hình đọc từ biến môi trường tiền tố ``CAMHUB_`` (hoặc tệp ``connector.env``)."""

    model_config = SettingsConfigDict(env_prefix="CAMHUB_", env_file=DEFAULT_CONFIG_FILE, extra="ignore")

    # Gốc HTTPS công khai của CamHub, ví dụ https://camhub.example.vn (chỉ mở kết nối ra ngoài cổng 443)
    server: str
    # Mã kích hoạt một lần (hạn 24 giờ), Admin lấy khi tạo bộ kết nối trên giao diện; chỉ cần ở lần chạy đầu
    activation_code: str = ""
    # Nơi lưu thông tin đăng nhập sau khi kích hoạt (quyền 0600)
    state_dir: Path = Path("/var/lib/camhub-connector")
    # Bỏ kiểm tra chứng chỉ TLS – CHỈ dùng cho môi trường dev với chứng chỉ tự ký
    tls_insecure: bool = False
    # CA riêng để xác minh chứng chỉ máy chủ (thay cho CA hệ thống)
    ca_file: Path | None = None
    heartbeat_seconds: float = 30.0
    chisel_bin: str = "chisel"
    # Fingerprint khóa SSH của Tunnel Server (chống MITM khi đã có TLS thì không bắt buộc)
    chisel_fingerprint: str = ""
    # Card mạng đi ra máy chủ để đo lưu lượng; để trống thì tự chọn theo bảng định tuyến
    wan_interface: str = ""
    # Danh sách IP/CIDR dò thêm bằng Probe unicast khi mạng chặn multicast, cách nhau bởi dấu phẩy
    discovery_targets: str = ""

    @field_validator("server")
    @classmethod
    def must_use_https(cls, v: str) -> str:
        if not v.startswith("https://"):
            raise ValueError("server phải dùng https:// (chỉ mở kết nối ra ngoài qua TLS cổng 443)")
        return v.rstrip("/")

    @field_validator("heartbeat_seconds")
    @classmethod
    def positive_interval(cls, v: float) -> float:
        if v < 1:
            raise ValueError("heartbeat_seconds phải >= 1")
        return v

    def discovery_target_list(self) -> list[str]:
        return [t.strip() for t in self.discovery_targets.split(",") if t.strip()]

    @property
    def credentials_file(self) -> Path:
        return self.state_dir / "credentials.json"

    @property
    def health_file(self) -> Path:
        return self.state_dir / "health.json"
