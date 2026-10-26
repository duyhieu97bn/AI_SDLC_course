"""Liệt kê kênh của đầu ghi NVR/DVR qua ONVIF (US-03.1).

Mỗi kênh là một ``tt:VideoSource`` (GetVideoSources, Media ver10), đánh số từ 1 theo thứ tự thiết bị trả.
Profile (GetProfiles) được gom theo ``VideoSourceConfiguration/SourceToken``: profile độ phân giải cao nhất
là luồng chính, thấp nhất là luồng phụ (khi kênh có từ hai profile). URI RTSP lấy bằng GetStreamUri và trả
nguyên như thiết bị báo (có thể là địa chỉ LAN của site; Core tự nối qua tunnel nếu cần).

Quy tắc suy đoán (đầu ghi các hãng báo "mất tín hiệu" rất khác nhau):

- ``hasSignal = False`` khi kênh không có profile video nào; hoặc ``tt:VideoSource`` khai báo ``Framerate`` ≤ 0
  hay ``Resolution`` 0×0 (Hikvision/Dahua và bộ giả lập dùng cách này cho kênh trống); hoặc thiết bị không trả
  được URI luồng chính.
- Tên kênh: ``VideoSourceConfiguration/Name`` nếu tên đó không trùng với kênh khác (nhiều đầu ghi đặt tên kênh
  ở đây); không thì tên profile chính nếu không trùng; cuối cùng là ``Kênh NN``.
- Nguồn video chỉ xuất hiện trong profile (thiếu trong GetVideoSources) vẫn được tính là một kênh, xếp sau.
"""

from __future__ import annotations

import asyncio
import logging
from collections import Counter
from dataclasses import dataclass, field

from app.models import ChannelResolution, RecorderChannel, RecorderChannels
from app.onvif.client import OnvifClient, ProfileData, VideoSourceData
from app.onvif.errors import DeviceError, ErrorCode

log = logging.getLogger(__name__)

_FATAL = (ErrorCode.UNREACHABLE, ErrorCode.AUTH_FAILED)
# Số lời gọi GetStreamUri chạy song song: đủ nhanh với đầu ghi 32 kênh mà không làm thiết bị quá tải
_URI_CONCURRENCY = 6


@dataclass
class _Group:
    source: VideoSourceData
    profiles: list[ProfileData] = field(default_factory=list)

    @property
    def main(self) -> ProfileData | None:
        ranked = self._ranked()
        return ranked[0] if ranked else None

    @property
    def sub(self) -> ProfileData | None:
        ranked = self._ranked()
        return ranked[-1] if len(ranked) > 1 else None

    def _ranked(self) -> list[ProfileData]:
        # sorted ổn định: cùng độ phân giải thì giữ thứ tự thiết bị trả
        return sorted(self.profiles, key=lambda p: -_area(p))


def _area(profile: ProfileData) -> int:
    enc = profile.encoder
    return enc.width * enc.height if enc is not None else 0


def _source_reports_no_signal(source: VideoSourceData) -> bool:
    if source.framerate is not None and source.framerate <= 0:
        return True
    return source.resolution is not None and (source.resolution[0] <= 0 or source.resolution[1] <= 0)


def _pick_names(groups: list[_Group]) -> list[str]:
    def first_config_name(group: _Group) -> str:
        return next((p.video_source_config_name for p in group.profiles if p.video_source_config_name), "") or ""

    def main_name(group: _Group) -> str:
        main = group.main
        return main.name.strip() if main is not None else ""

    config_names = [first_config_name(g).strip() for g in groups]
    profile_names = [main_name(g) for g in groups]
    config_count, profile_count = Counter(config_names), Counter(profile_names)
    names = []
    for index, (config_name, profile_name) in enumerate(zip(config_names, profile_names, strict=True), start=1):
        if config_name and config_count[config_name] == 1:
            names.append(config_name)
        elif profile_name and profile_count[profile_name] == 1:
            names.append(profile_name)
        else:
            names.append(f"Kênh {index:02d}")
    return names


async def _group_profiles(client: OnvifClient) -> list[_Group]:
    profiles = [p for p in await client.get_profiles() if p.video_source_token and p.encoder is not None]
    try:
        sources = await client.get_video_source_details()
    except DeviceError as e:
        if e.code in _FATAL:
            raise
        log.info("Thiết bị %s không trả danh sách nguồn video: %s", client.target.label, e.detail)
        sources = []
    groups = {s.token: _Group(s) for s in sources}
    for profile in profiles:
        token = profile.video_source_token or ""
        if token not in groups:
            groups[token] = _Group(VideoSourceData(token, None, None))
        groups[token].profiles.append(profile)
    return list(groups.values())


async def list_channels(client: OnvifClient) -> RecorderChannels:
    await client.connect()
    info = await client.get_device_information()
    groups = await _group_profiles(client)
    if not groups:
        raise DeviceError(ErrorCode.NOT_SUPPORTED, "Thiết bị không có nguồn video")

    semaphore = asyncio.Semaphore(_URI_CONCURRENCY)

    async def stream_uri(profile: ProfileData | None) -> str | None:
        if profile is None:
            return None
        async with semaphore:
            try:
                return await client.get_stream_uri(profile.token)
            except DeviceError as e:
                if e.code in _FATAL:
                    raise
                log.info("Không lấy được URI luồng profile %s của %s: %s", profile.token, client.target.label, e.detail)
                return None

    jobs = [stream_uri(p) for g in groups for p in (g.main, g.sub)]
    results = await asyncio.gather(*jobs, return_exceptions=True)
    for result in results:
        if isinstance(result, BaseException):
            raise result
    uris = [r if isinstance(r, str) and r else None for r in results]

    channels = []
    for index, (group, name) in enumerate(zip(groups, _pick_names(groups), strict=True)):
        main, sub = group.main, group.sub
        main_uri, sub_uri = uris[2 * index], uris[2 * index + 1]
        enc = main.encoder if main is not None else None
        resolution = None
        if enc is not None and enc.width > 0 and enc.height > 0:
            resolution = ChannelResolution(width=enc.width, height=enc.height)
        elif group.source.resolution and group.source.resolution[0] > 0 and group.source.resolution[1] > 0:
            resolution = ChannelResolution(width=group.source.resolution[0], height=group.source.resolution[1])
        channels.append(
            RecorderChannel(
                channel=index + 1,
                name=name,
                video_source_token=group.source.token,
                profile_token=main.token if main is not None else None,
                sub_profile_token=sub.token if sub is not None else None,
                has_signal=main is not None and main_uri is not None and not _source_reports_no_signal(group.source),
                resolution=resolution,
                rtsp_url=main_uri,
                sub_rtsp_url=sub_uri,
            )
        )
    return RecorderChannels(
        manufacturer=info.manufacturer,
        model=info.model,
        channel_count=len(channels),
        channels=channels,
    )
