"""Đọc thông tin, năng lực và profile luồng của thiết bị thành ``DeviceInfo`` (SADD 3.3, module capabilities)."""

from __future__ import annotations

import logging
from typing import Literal

from app.models import Capabilities, DeviceInfo, StreamProfile
from app.onvif.client import OnvifClient, ProfileData
from app.onvif.errors import DeviceError, ErrorCode

log = logging.getLogger(__name__)

ProfileType = Literal["MAIN", "SUB", "OTHER"]
# Lỗi kết nối/tài khoản phải báo ngay; lỗi khác ở bước phụ chỉ làm thiếu thông tin
_FATAL = (ErrorCode.UNREACHABLE, ErrorCode.AUTH_FAILED)


def classify_profiles(profiles: list[tuple[str, int, int]]) -> dict[str, ProfileType]:
    """Profile độ phân giải cao nhất là MAIN, độ phân giải khác cao kế tiếp là SUB, còn lại OTHER.

    Khi nhiều profile cùng độ phân giải, profile đứng trước (theo thứ tự thiết bị trả) được chọn.
    """
    areas = sorted({w * h for _, w, h in profiles if w * h > 0}, reverse=True)
    result: dict[str, ProfileType] = {token: "OTHER" for token, _, _ in profiles}
    kinds: tuple[ProfileType, ...] = ("MAIN", "SUB")
    for area, kind in zip(areas[:2], kinds, strict=False):
        token = next(t for t, w, h in profiles if w * h == area)
        result[token] = kind
    return result


def _bool_attr(value: str | None) -> bool:
    return (value or "").strip().lower() in ("true", "1")


def _int_attr(value: str | None) -> int:
    try:
        return int(value or "0")
    except ValueError:
        return 0


async def describe(client: OnvifClient) -> DeviceInfo:
    await client.connect()
    info = await client.get_device_information()
    profiles = await client.get_profiles()

    video = [p for p in profiles if p.encoder is not None]
    types = classify_profiles([(p.token, p.encoder.width, p.encoder.height) for p in video if p.encoder])
    stream_profiles = [await _stream_profile(client, p, types[p.token]) for p in video]

    mac = None
    try:
        nics = await client.get_network_interfaces()
        mac = next((n.mac_address for n in nics if n.enabled and n.mac_address), None)
    except DeviceError as e:
        if e.code in _FATAL:
            raise
        log.info("Không đọc được giao diện mạng của %s: %s", client.target.netloc, e.detail)

    events = client.has_service("events")
    motion = events
    if events:
        try:
            motion = await client.supports_motion_events()
        except DeviceError as e:
            if e.code in _FATAL:
                raise
    caps = client.service_caps
    deviceio = caps.get("deviceio", {})
    capabilities = Capabilities(
        ptz=client.has_service("ptz") and any(p.has_ptz for p in profiles),
        audio_in=any(p.has_audio_in for p in profiles) or _int_attr(deviceio.get("AudioSources")) > 0,
        audio_out=any(p.has_audio_out for p in profiles) or _int_attr(deviceio.get("AudioOutputs")) > 0,
        imaging=client.has_service("imaging"),
        events=events,
        edge_recording=client.has_service("recording") or client.has_service("search"),
        motion_detection=motion,
        privacy_mask=_bool_attr(caps.get("media2", {}).get("Mask")),
    )
    return DeviceInfo(
        manufacturer=info.manufacturer,
        model=info.model,
        firmware=info.firmware,
        serial_number=info.serial_number,
        mac_address=mac,
        capabilities=capabilities,
        profiles=stream_profiles,
    )


async def _stream_profile(client: OnvifClient, profile: ProfileData, kind: ProfileType) -> StreamProfile:
    enc = profile.encoder
    assert enc is not None
    uri = None
    try:
        uri = await client.get_stream_uri(profile.token)
    except DeviceError as e:
        if e.code in _FATAL:
            raise
        log.info("Không lấy được URI luồng profile %s của %s: %s", profile.token, client.target.netloc, e.detail)
    return StreamProfile(
        token=profile.token,
        name=profile.name,
        type=kind,
        codec=enc.codec,
        width=enc.width,
        height=enc.height,
        fps=enc.fps,
        bitrate_kbps=enc.bitrate_kbps,
        rtsp_url=uri,
        b_frames=enc.b_frames,
    )
