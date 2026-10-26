"""Lỗi thiết bị với mã ổn định, khớp ``DeviceControlPort.DeviceException`` phía Core."""

from enum import StrEnum


class ErrorCode(StrEnum):
    AUTH_FAILED = "AUTH_FAILED"
    UNREACHABLE = "UNREACHABLE"
    NOT_ONVIF = "NOT_ONVIF"
    NOT_SUPPORTED = "NOT_SUPPORTED"
    DEVICE_ERROR = "DEVICE_ERROR"
    # Yêu cầu vượt giới hạn thiết bị (số vùng, số điểm) hoặc vùng không hợp lệ với lưới của thiết bị
    LIMIT_EXCEEDED = "LIMIT_EXCEEDED"
    INVALID_ZONE = "INVALID_ZONE"


class DeviceError(Exception):
    """Lỗi khi gọi thiết bị. ``detail`` là mô tả cho người vận hành, không chứa mật khẩu."""

    def __init__(self, code: ErrorCode, detail: str) -> None:
        super().__init__(f"{code}: {detail}")
        self.code = code
        self.detail = detail
