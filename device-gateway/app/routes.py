"""REST nội bộ ``/v1`` cho Core (TS-05.5). Lỗi thiết bị trả HTTP 424 ``{"code", "detail"}``."""

from __future__ import annotations

import asyncio
import contextlib
import logging
from dataclasses import dataclass
from typing import Annotated

from fastapi import APIRouter, Depends, Request, Response, WebSocket, status
from fastapi.responses import JSONResponse

from app.capabilities.service import classify_profiles, describe
from app.control import network, zones
from app.discovery.recorder_channels import list_channels
from app.discovery.ws_discovery import discover
from app.edge_playback import service as edge_playback
from app.events.health_monitor import HealthMonitor
from app.events.manager import SubscriptionManager
from app.events.publisher import EventSink
from app.models import (
    DeviceAddress,
    DeviceInfo,
    DeviceRequest,
    DiscoveredDevice,
    DiscoverRequest,
    EncoderConfig,
    EncoderSetRequest,
    ImagingGetRequest,
    ImagingSetRequest,
    ImagingSettings,
    Message,
    MonitorTargetsRequest,
    MonitorTargetStatus,
    MotionZonesSetRequest,
    NetworkInfo,
    NetworkSetRequest,
    NetworkSetResult,
    NtpSetRequest,
    PasswordRequest,
    Preset,
    PresetGotoRequest,
    PresetSetRequest,
    PresetToken,
    PrivacyMasksSetRequest,
    PtzMoveRequest,
    PtzRequest,
    RecorderChannels,
    RecordingSearchRequest,
    RecordingSearchResult,
    ReplayUri,
    ReplayUriRequest,
    SnapshotRequest,
    SubscriptionRequest,
    SubscriptionStatus,
    TalkSessionCreated,
    TalkStatus,
    TimeSetRequest,
    ZonesConfig,
)
from app.onvif.client import DeviceTarget, OnvifClient, SessionCache
from app.onvif.errors import DeviceError, ErrorCode
from app.settings import Settings
from app.talk.sessions import TalkManager, TalkSession
from app.transport.tunnel_router import TunnelRouter

log = logging.getLogger(__name__)
_FATAL = (ErrorCode.UNREACHABLE, ErrorCode.AUTH_FAILED)


@dataclass
class GatewayState:
    settings: Settings
    router: TunnelRouter
    cache: SessionCache
    sink: EventSink
    manager: SubscriptionManager
    monitor: HealthMonitor
    talk: TalkManager

    @staticmethod
    def target(device: DeviceAddress) -> DeviceTarget:
        return DeviceTarget(
            device.host.strip(), device.port, device.username, device.password.get_secret_value(), device.proxy
        )

    def client_for_target(self, target: DeviceTarget) -> OnvifClient:
        return OnvifClient(
            target,
            self.router.client(target.proxy),
            timeout=self.settings.device_timeout_seconds,
            cache=self.cache,
            rewrite_xaddr=self.settings.rewrite_xaddr,
        )

    async def connect(self, device: DeviceAddress) -> OnvifClient:
        client = self.client_for_target(self.target(device))
        await client.connect()
        return client


def get_state(request: Request) -> GatewayState:
    state: GatewayState = request.app.state.gw
    return state


State = Annotated[GatewayState, Depends(get_state)]

router = APIRouter(prefix="/v1")


# ---- Dò tìm và thông tin ------------------------------------------------------------------------------


@router.post("/discovery", response_model=list[DiscoveredDevice])
async def post_discovery(body: DiscoverRequest, gw: State) -> list[DiscoveredDevice] | JSONResponse:
    try:
        return await discover(
            body.targets,
            body.timeout_seconds,
            port=gw.settings.discovery_port,
            multicast=gw.settings.discovery_multicast,
        )
    except ValueError as e:
        return JSONResponse({"code": "INVALID_TARGET", "detail": str(e)}, status_code=422)


@router.post("/devices/info", response_model=DeviceInfo)
async def post_device_info(device: DeviceAddress, gw: State) -> DeviceInfo:
    return await describe(gw.client_for_target(gw.target(device)))


@router.post("/devices/recorder/channels", response_model=RecorderChannels)
async def post_recorder_channels(body: DeviceRequest, gw: State) -> RecorderChannels:
    return await list_channels(await gw.connect(body.device))


# ---- Phát lại bản ghi trên thiết bị (US-22.1, Profile G) -----------------------------------------------


@router.post("/devices/recordings/search", response_model=RecordingSearchResult)
async def recordings_search(body: RecordingSearchRequest, gw: State) -> RecordingSearchResult:
    client = await gw.connect(body.device)
    return await edge_playback.search_recordings(client, body.from_, body.to, body.video_source_token)


@router.post("/devices/recordings/replay-uri", response_model=ReplayUri)
async def recordings_replay_uri(body: ReplayUriRequest, gw: State) -> ReplayUri:
    client = await gw.connect(body.device)
    return ReplayUri(uri=await edge_playback.replay_uri(client, body.recording_token, body.start))


# ---- PTZ ----------------------------------------------------------------------------------------------


@router.post("/devices/ptz/move", status_code=204, response_class=Response)
async def ptz_move(body: PtzMoveRequest, gw: State) -> None:
    client = await gw.connect(body.device)
    await client.ptz_continuous_move(body.profile_token, body.pan, body.tilt, body.zoom, body.timeout_ms)


@router.post("/devices/ptz/stop", status_code=204, response_class=Response)
async def ptz_stop(body: PtzRequest, gw: State) -> None:
    client = await gw.connect(body.device)
    await client.ptz_stop(body.profile_token)


@router.post("/devices/ptz/presets", response_model=list[Preset])
async def ptz_presets(body: PtzRequest, gw: State) -> list[Preset]:
    client = await gw.connect(body.device)
    return await client.ptz_get_presets(body.profile_token)


@router.post("/devices/ptz/presets/goto", status_code=204, response_class=Response)
async def ptz_goto(body: PresetGotoRequest, gw: State) -> None:
    client = await gw.connect(body.device)
    await client.ptz_goto_preset(body.profile_token, body.preset_token)


@router.post("/devices/ptz/presets/set", response_model=PresetToken)
async def ptz_set_preset(body: PresetSetRequest, gw: State) -> PresetToken:
    client = await gw.connect(body.device)
    return PresetToken(token=await client.ptz_set_preset(body.profile_token, body.name, body.preset_token))


@router.post("/devices/ptz/presets/remove", status_code=204, response_class=Response)
async def ptz_remove_preset(body: PresetGotoRequest, gw: State) -> None:
    client = await gw.connect(body.device)
    await client.ptz_remove_preset(body.profile_token, body.preset_token)


# ---- Hình ảnh -----------------------------------------------------------------------------------------


@router.post("/devices/imaging/get", response_model=ImagingSettings)
async def imaging_get(body: ImagingGetRequest, gw: State) -> ImagingSettings:
    client = await gw.connect(body.device)
    source = body.video_source_token or await client.default_video_source()
    settings = await client.get_imaging_settings(source)
    try:
        settings.options = await client.get_imaging_options(source)
    except DeviceError as e:
        if e.code in _FATAL:
            raise
        log.info("Thiết bị %s không trả tùy chọn hình ảnh: %s", client.target.netloc, e.detail)
    return settings


@router.post("/devices/imaging/set", status_code=204, response_class=Response)
async def imaging_set(body: ImagingSetRequest, gw: State) -> None:
    client = await gw.connect(body.device)
    source = body.video_source_token or await client.default_video_source()
    await client.set_imaging_settings(source, body.settings)


# ---- Bộ mã hóa ----------------------------------------------------------------------------------------


@router.post("/devices/encoder/get", response_model=list[EncoderConfig])
async def encoder_get(body: DeviceRequest, gw: State) -> list[EncoderConfig]:
    client = await gw.connect(body.device)
    result = []
    for enc in await client.get_video_encoder_configurations():
        options = None
        try:
            options = await client.get_video_encoder_options(enc.token)
        except DeviceError as e:
            if e.code in _FATAL:
                raise
        result.append(
            EncoderConfig(
                token=enc.token,
                name=enc.name,
                codec=enc.codec,
                width=enc.width,
                height=enc.height,
                fps=enc.fps,
                bitrate_kbps=enc.bitrate_kbps,
                gov_length=enc.gov_length,
                quality=enc.quality,
                h264_profile=enc.h264_profile,
                b_frames=enc.b_frames,
                options=options,
            )
        )
    return result


@router.post("/devices/encoder/set", status_code=204, response_class=Response)
async def encoder_set(body: EncoderSetRequest, gw: State) -> None:
    client = await gw.connect(body.device)
    await client.set_video_encoder_configuration(
        body.configuration_token,
        codec=body.codec,
        width=body.width,
        height=body.height,
        fps=body.fps,
        bitrate_kbps=body.bitrate_kbps,
        gov_length=body.gov_length,
    )


# ---- Mạng, thời gian, hệ thống ------------------------------------------------------------------------


@router.post("/devices/network/get", response_model=NetworkInfo)
async def network_get(body: DeviceRequest, gw: State) -> NetworkInfo:
    return await network.get_network(await gw.connect(body.device))


@router.post("/devices/network/set", response_model=NetworkSetResult)
async def network_set(body: NetworkSetRequest, gw: State) -> NetworkSetResult:
    return await network.set_network(await gw.connect(body.device), body)


@router.post("/devices/ntp/set", status_code=204, response_class=Response)
async def ntp_set(body: NtpSetRequest, gw: State) -> None:
    client = await gw.connect(body.device)
    await client.set_ntp(body.servers)


@router.post("/devices/time/set", status_code=204, response_class=Response)
async def time_set(body: TimeSetRequest, gw: State) -> None:
    await network.set_time(await gw.connect(body.device), body)


@router.post("/devices/reboot", response_model=Message)
async def reboot(body: DeviceRequest, gw: State) -> Message:
    client = await gw.connect(body.device)
    return Message(message=await client.system_reboot() or "Thiết bị đang khởi động lại")


@router.post("/devices/password", status_code=204, response_class=Response)
async def change_password(body: PasswordRequest, gw: State) -> None:
    client = await gw.connect(body.device)
    await client.set_user_password(body.username, body.new_password.get_secret_value())
    log.info("Đã đổi mật khẩu tài khoản %s trên thiết bị %s", body.username, client.target.netloc)


@router.post("/devices/snapshot", response_class=Response, responses={200: {"content": {"image/jpeg": {}}}})
async def snapshot(body: SnapshotRequest, gw: State) -> Response:
    client = await gw.connect(body.device)
    token = body.profile_token
    if not token:
        profiles = [p for p in await client.get_profiles() if p.encoder is not None]
        if not profiles:
            raise DeviceError(ErrorCode.NOT_SUPPORTED, "Thiết bị không có profile video")
        kinds = classify_profiles([(p.token, p.encoder.width, p.encoder.height) for p in profiles if p.encoder])
        token = next((t for t, k in kinds.items() if k == "MAIN"), profiles[0].token)
    uri = await client.get_snapshot_uri(token)
    if not uri:
        raise DeviceError(ErrorCode.NOT_SUPPORTED, "Thiết bị không cung cấp URI ảnh chụp")
    content, media_type = await client.fetch_snapshot(uri)
    return Response(content=content, media_type=media_type, headers={"Cache-Control": "no-store"})


# ---- Vùng chuyển động và vùng che riêng tư (US-26) ------------------------------------------------------


@router.post("/devices/zones/get", response_model=ZonesConfig)
async def zones_get(body: DeviceRequest, gw: State) -> ZonesConfig:
    return await zones.get_zones(await gw.connect(body.device))


@router.post("/devices/zones/motion/set", status_code=204, response_class=Response)
async def zones_motion_set(body: MotionZonesSetRequest, gw: State) -> None:
    await zones.set_motion(await gw.connect(body.device), body)


@router.post("/devices/zones/masks/set", status_code=204, response_class=Response)
async def zones_masks_set(body: PrivacyMasksSetRequest, gw: State) -> None:
    await zones.set_masks(await gw.connect(body.device), body)


# ---- Sự kiện ------------------------------------------------------------------------------------------


@router.post("/events/subscriptions", response_model=SubscriptionStatus, status_code=status.HTTP_202_ACCEPTED)
async def subscribe(body: SubscriptionRequest, gw: State) -> SubscriptionStatus:
    return await gw.manager.start(body.camera_id, gw.target(body.device))


@router.get("/events/subscriptions", response_model=list[SubscriptionStatus])
async def list_subscriptions(gw: State) -> list[SubscriptionStatus]:
    return gw.manager.statuses()


@router.get("/events/subscriptions/{camera_id}", response_model=SubscriptionStatus)
async def get_subscription(camera_id: str, gw: State) -> SubscriptionStatus | JSONResponse:
    found = gw.manager.get(camera_id)
    if found is None:
        return JSONResponse({"code": "SUBSCRIPTION_NOT_FOUND", "detail": camera_id}, status_code=404)
    return found


@router.delete("/events/subscriptions/{camera_id}", status_code=204, response_class=Response)
async def unsubscribe(camera_id: str, gw: State) -> Response:
    if not await gw.manager.stop(camera_id):
        return JSONResponse({"code": "SUBSCRIPTION_NOT_FOUND", "detail": camera_id}, status_code=404)
    return Response(status_code=status.HTTP_204_NO_CONTENT)


# ---- Theo dõi trạng thái (US-04.1) ---------------------------------------------------------------------


@router.put("/monitor/targets", response_model=list[MonitorTargetStatus])
async def put_monitor_targets(body: MonitorTargetsRequest, gw: State) -> list[MonitorTargetStatus] | JSONResponse:
    ids = [t.camera_id for t in body.targets]
    if len(ids) != len(set(ids)):
        return JSONResponse({"code": "DUPLICATE_CAMERA", "detail": "cameraId bị lặp"}, status_code=422)
    return await gw.monitor.set_targets(body.targets, body.interval_seconds)


@router.get("/monitor/targets", response_model=list[MonitorTargetStatus])
async def get_monitor_targets(gw: State) -> list[MonitorTargetStatus]:
    return gw.monitor.statuses()


# ---- Đàm thoại (US-15) ---------------------------------------------------------------------------------

# Khung PCM lớn nhất nhận từ trình duyệt (1 giây âm thanh 8 kHz 16 bit là 16 KB)
_MAX_TALK_FRAME = 64 * 1024


def _talk_status(session: TalkSession) -> TalkStatus:
    return TalkStatus(
        session_id=session.id,
        state=session.state,
        started_at=session.started_at,
        ended_at=session.ended_at,
        duration_seconds=round(session.duration_seconds, 1),
        audio_seconds=round(session.audio_seconds, 1),
        error=session.error,
    )


@router.post("/talk/sessions", response_model=TalkSessionCreated, status_code=status.HTTP_201_CREATED)
async def talk_open(body: DeviceRequest, gw: State) -> TalkSessionCreated:
    target = gw.target(body.device)

    async def opener(host: str, port: int) -> tuple[asyncio.StreamReader, asyncio.StreamWriter]:
        return await gw.router.open_connection(host, port, target.proxy)

    session = await gw.talk.create(
        f"{target.proxy or ''}|{target.netloc}",
        gw.client_for_target(target),
        opener,
        target.username,
        target.password,
    )
    return TalkSessionCreated(
        session_id=session.id,
        codec=session.channel.codec,  # type: ignore[arg-type]
        expires_in_seconds=int(gw.settings.talk_ready_timeout_seconds),
    )


@router.get("/talk/sessions/{session_id}", response_model=TalkStatus)
async def talk_get(session_id: str, gw: State) -> TalkStatus | JSONResponse:
    session = gw.talk.get(session_id)
    if session is None:
        return JSONResponse({"code": "TALK_SESSION_NOT_FOUND", "detail": "Phiên không tồn tại"}, status_code=404)
    return _talk_status(session)


@router.delete("/talk/sessions/{session_id}", response_model=TalkStatus)
async def talk_close(session_id: str, gw: State) -> TalkStatus | JSONResponse:
    session = await gw.talk.end(session_id)
    if session is None:
        return JSONResponse({"code": "TALK_SESSION_NOT_FOUND", "detail": "Phiên không tồn tại"}, status_code=404)
    return _talk_status(session)


# WebSocket nằm ngoài /v1: trình duyệt (qua nginx) nối thẳng, xác thực bằng mã phiên Core cấp
ws_router = APIRouter()


@ws_router.websocket("/talk/ws/{session_id}")
async def talk_ws(websocket: WebSocket, session_id: str) -> None:
    """Nhận PCM 16 bit little-endian mono 8 kHz (khung nhị phân); text ``end`` hoặc đóng socket là kết thúc."""
    gw: GatewayState = websocket.app.state.gw
    session = gw.talk.attach(session_id)
    if session is None:
        await websocket.close(code=4404)
        return
    await websocket.accept()
    error: str | None = None
    try:
        while True:
            message = await websocket.receive()
            if message["type"] == "websocket.disconnect":
                break
            data = message.get("bytes")
            if data:
                if len(data) <= _MAX_TALK_FRAME:
                    await session.channel.send_pcm(data)
            elif message.get("text") == "end":
                break
    except DeviceError as e:
        error = e.detail
    finally:
        await gw.talk.end(session_id, error)
        with contextlib.suppress(Exception):
            await websocket.close(code=1000 if error is None else 4502)
