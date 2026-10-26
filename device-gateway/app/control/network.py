"""Mạng và thời gian của thiết bị (US-25.1).

- Đọc: IPv4 của card mạng đầu tiên đang bật, gateway, DNS, NTP, múi giờ và độ lệch đồng hồ so với Gateway.
- Đổi mạng: ``SetDNS`` → ``SetNetworkDefaultGateway`` → ``SetNetworkInterfaces`` (đổi IP sau cùng, vì sau bước đó
  thiết bị có thể không còn trả lời ở địa chỉ cũ).
- Đổi giờ: ``SetNTP`` (chế độ NTP, khi có danh sách máy chủ) rồi ``SetSystemDateAndTime``.
"""

from __future__ import annotations

import logging
from collections.abc import Awaitable
from datetime import UTC, datetime
from typing import Final

from app.events.normalize import format_instant
from app.models import NetworkInfo, NetworkSetRequest, NetworkSetResult, TimeMode, TimeSetRequest
from app.onvif.client import NetworkInterface, OnvifClient
from app.onvif.errors import DeviceError, ErrorCode

log = logging.getLogger(__name__)

# Lỗi kết nối/tài khoản phải báo ngay; lỗi khác ở phần thông tin phụ chỉ làm phần đó trống
_FATAL = (ErrorCode.UNREACHABLE, ErrorCode.AUTH_FAILED)
# Giờ ONVIF chỉ có đến giây (bị cắt phần lẻ) nên trung bình chậm 0,5 giây so với thật
_TRUNCATION_BIAS: Final = 0.5
_TIME_MODES: Final[dict[str, TimeMode]] = {"NTP": "NTP", "MANUAL": "MANUAL"}


async def _optional[T](call: Awaitable[T], default: T) -> T:
    try:
        return await call
    except DeviceError as e:
        if e.code in _FATAL:
            raise
        return default


def _primary(nics: list[NetworkInterface]) -> NetworkInterface | None:
    return next((n for n in nics if n.enabled and n.ipv4_address), next((n for n in nics if n.enabled), None))


async def get_network(client: OnvifClient) -> NetworkInfo:
    # Độ lệch đồng hồ = giờ thiết bị − giờ Gateway tại mốc giữa lúc gửi và lúc nhận
    sent = datetime.now(UTC)
    clock = await client.get_system_date_and_time()
    received = datetime.now(UTC)
    drift = (clock.utc - (sent + (received - sent) / 2)).total_seconds() + _TRUNCATION_BIAS
    nic = _primary(await client.get_network_interfaces())
    ntp_from_dhcp, servers = await _optional(client.get_ntp(), (False, list[str]()))
    hostname = await _optional(client.get_hostname(), None)
    gateway = await _optional(client.get_default_gateway(), None)
    dns = await _optional(client.get_dns(), list[str]())
    return NetworkInfo(
        ip_address=nic.ipv4_address if nic else None,
        prefix_length=nic.prefix_length if nic else None,
        gateway=gateway,
        dns=dns,
        mac_address=nic.mac_address if nic else None,
        hostname=hostname,
        ntp_servers=servers,
        ntp_from_dhcp=ntp_from_dhcp,
        from_dhcp=nic.from_dhcp if nic else False,
        time_mode=_TIME_MODES.get((clock.date_time_type or "").upper()),
        timezone=clock.timezone,
        device_time=format_instant(clock.utc),
        clock_drift_seconds=round(drift, 1),
    )


async def set_network(client: OnvifClient, body: NetworkSetRequest) -> NetworkSetResult:
    nic = _primary(await client.get_network_interfaces())
    if nic is None:
        raise DeviceError(ErrorCode.NOT_SUPPORTED, "Thiết bị không có card mạng nào đang bật")
    if body.dns is not None:
        await client.set_dns(body.dns)
    if body.gateway is not None:
        await client.set_default_gateway(body.gateway)
    reboot = await client.set_network_interface(nic.token, body.dhcp, body.address, body.prefix_length)
    new_host = body.address if not body.dhcp and body.address != nic.ipv4_address else None
    log.info(
        "Đã đổi mạng thiết bị %s: %s (khởi động lại: %s)",
        client.target.netloc,
        "DHCP" if body.dhcp else f"{body.address}/{body.prefix_length}",
        reboot,
    )
    return NetworkSetResult(reboot_needed=reboot, new_host=new_host)


async def set_time(client: OnvifClient, body: TimeSetRequest) -> None:
    current = await client.get_system_date_and_time()
    timezone = body.timezone or current.timezone
    # Múi giờ mới có quy tắc DST (sau dấu phẩy) thì để thiết bị tự chỉnh giờ mùa hè
    daylight = "," in body.timezone if body.timezone else current.daylight_savings
    if body.mode == "NTP":
        if body.ntp_servers:
            await client.set_ntp_servers(body.ntp_servers)
        await client.set_system_date_and_time("NTP", daylight, timezone)
    else:
        await client.set_system_date_and_time("Manual", daylight, timezone, body.utc)
    log.info("Đã đặt giờ thiết bị %s: %s, múi giờ %s", client.target.netloc, body.mode, timezone)
