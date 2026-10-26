"""Ứng dụng HTTP của camera giả lập: các dịch vụ ONVIF (SOAP) và API điều khiển ``/sim/*``."""

from __future__ import annotations

import asyncio
import base64
import binascii
import contextlib
import hmac
import ipaddress
import logging
import time
from collections.abc import AsyncIterator, Callable
from contextlib import asynccontextmanager
from datetime import UTC, datetime, timedelta
from xml.etree.ElementTree import Element  # noqa: S405 - chỉ dùng kiểu

from fastapi import FastAPI, Request, Response
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from onvif_sim import __version__
from onvif_sim.backchannel import BackchannelServer
from onvif_sim.cells import decode_active_cells
from onvif_sim.config import SimConfig
from onvif_sim.discovery import DiscoveryResponder
from onvif_sim.recordings import EdgeRecordingService
from onvif_sim.snapshot import SNAPSHOT_JPEG
from onvif_sim.soap import (
    NS,
    SOAP_CONTENT_TYPE,
    DigestVerifier,
    SoapFault,
    action_not_supported,
    attr,
    envelope,
    esc,
    fault_envelope,
    invalid_arg,
    iso,
    local,
    parse_duration,
    parse_request,
    txt,
)
from onvif_sim.state import (
    GRID_COLUMNS,
    GRID_ROWS,
    MAX_CELL_RULES,
    MAX_MASK_POINTS,
    MAX_MASKS,
    MAX_REGION_RULES,
    Encoder,
    Mask,
    Profile,
    Rule,
    SimState,
)

log = logging.getLogger("onvif_sim")

# Thao tác được gọi không cần xác thực (ONVIF Core: PRE_AUTH)
PRE_AUTH = {"GetSystemDateAndTime"}
CELL_TOPIC = "tns1:RuleEngine/CellMotionDetector/Motion"
ALARM_TOPIC = "tns1:VideoSource/MotionAlarm"
MAX_PULL_SECONDS = 30.0

Handler = Callable[[Element], str]


def _version(major: int, minor: int) -> str:
    return f"<tds:Version><tt:Major>{major}</tt:Major><tt:Minor>{minor}</tt:Minor></tds:Version>"


def _range(name: str, low: float, high: float) -> str:
    return f"<tt:{name}><tt:Min>{low:g}</tt:Min><tt:Max>{high:g}</tt:Max></tt:{name}>"


def _float(raw: str | None, field: str) -> float:
    try:
        return float(raw or "")
    except ValueError as e:
        raise invalid_arg(f"Giá trị {field} không hợp lệ") from e


class OnvifSimulator:
    def __init__(self, config: SimConfig) -> None:
        self.config = config
        self.state = SimState.create(config)
        self.verifier = DigestVerifier()
        device: dict[str, Handler] = {
            "GetSystemDateAndTime": self.get_system_date_and_time,
            "SetSystemDateAndTime": self.set_system_date_and_time,
            "GetDeviceInformation": self.get_device_information,
            "GetServices": self.get_services,
            "GetCapabilities": self.get_capabilities,
            "GetNetworkInterfaces": self.get_network_interfaces,
            "SetNetworkInterfaces": self.set_network_interfaces,
            "GetNetworkDefaultGateway": self.get_network_default_gateway,
            "SetNetworkDefaultGateway": self.set_network_default_gateway,
            "GetDNS": self.get_dns,
            "SetDNS": self.set_dns,
            "GetHostname": self.get_hostname,
            "GetNTP": self.get_ntp,
            "SetNTP": self.set_ntp,
            "SystemReboot": self.system_reboot,
            "GetUsers": self.get_users,
            "SetUser": self.set_user,
        }
        media: dict[str, Handler] = {
            "GetProfiles": self.get_profiles,
            "GetVideoSources": self.get_video_sources,
            "GetStreamUri": self.get_stream_uri,
            "GetSnapshotUri": self.get_snapshot_uri,
            "GetVideoEncoderConfigurations": self.get_video_encoder_configurations,
            "GetVideoEncoderConfiguration": self.get_video_encoder_configuration,
            "GetVideoEncoderConfigurationOptions": self.get_video_encoder_options,
            "SetVideoEncoderConfiguration": self.set_video_encoder_configuration,
        }
        ptz: dict[str, Handler] = {
            "ContinuousMove": self.continuous_move,
            "Stop": self.ptz_stop,
            "GetPresets": self.get_presets,
            "GotoPreset": self.goto_preset,
            "SetPreset": self.set_preset,
            "RemovePreset": self.remove_preset,
        }
        imaging: dict[str, Handler] = {
            "GetImagingSettings": self.get_imaging_settings,
            "SetImagingSettings": self.set_imaging_settings,
            "GetOptions": self.get_imaging_options,
        }
        events: dict[str, Handler] = {
            "GetEventProperties": self.get_event_properties,
            "CreatePullPointSubscription": self.create_pull_point,
        }
        analytics: dict[str, Handler] = {
            "GetServiceCapabilities": self.get_analytics_capabilities,
            "GetSupportedRules": self.get_supported_rules,
            "GetRules": self.get_rules,
            "CreateRules": self.create_rules,
            "ModifyRules": self.modify_rules,
            "DeleteRules": self.delete_rules,
            "GetAnalyticsModules": self.get_analytics_modules,
            "ModifyAnalyticsModules": self.modify_analytics_modules,
        }
        media2: dict[str, Handler] = {
            "GetMaskOptions": self.get_mask_options,
            "GetMasks": self.get_masks,
            "CreateMask": self.create_mask,
            "SetMask": self.set_mask,
            "DeleteMask": self.delete_mask,
        }
        self.services: dict[str, dict[str, Handler]] = {
            "device_service": device,
            "media_service": media,
            "imaging_service": imaging,
            "events_service": events,
        }
        if config.ptz:
            self.services["ptz_service"] = ptz
        if config.zones:
            self.services["analytics_service"] = analytics
            self.services["media2_service"] = media2
        # Ghi hình trên thiết bị (Profile G, US-22.1): đầu ghi luôn có, camera đơn khi SIM_EDGE_RECORDING=true
        self.edge = EdgeRecordingService(self.state) if config.has_edge_recording else None
        if self.edge is not None:
            self.services["recording_service"] = self.edge.recording
            self.services["search_service"] = self.edge.search
            self.services["replay_service"] = self.edge.replay

    # ---- điều phối ------------------------------------------------------------------------------------

    async def handle(self, service: str, content: bytes) -> tuple[int, bytes]:
        try:
            header, request = parse_request(content)
            op = local(request.tag)
            handlers = self.services.get(service)
            if handlers is None or op not in handlers:
                raise action_not_supported(op)
            if op not in PRE_AUTH or (header is not None and header.find(".//{*}Security") is not None):
                self.verifier.verify(header, self.state.passwords(), self.state.now())
            return 200, envelope(handlers[op](request))
        except SoapFault as fault:
            if fault.subcode != "ter:NotAuthorized":
                log.info("SOAP Fault %s: %s", fault.subcode, fault.reason)
            return fault.http_status, fault_envelope(fault)

    async def handle_subscription(self, sub_id: str, content: bytes) -> tuple[int, bytes]:
        try:
            header, request = parse_request(content)
            self.verifier.verify(header, self.state.passwords(), self.state.now())
            self.state.expire_subscriptions()
            sub = self.state.subscriptions.get(sub_id)
            if sub is None:
                raise SoapFault("Sender", "wsrf-rw:ResourceUnknownFault", "Subscription không tồn tại hoặc đã hết hạn")
            op = local(request.tag)
            if op == "PullMessages":
                return 200, envelope(await self.pull_messages(sub.id, request))
            if op == "Renew":
                ttl = parse_duration(txt(request, "{*}TerminationTime"), 60.0)
                sub.expires = time.monotonic() + ttl
                now = self.state.now()
                return 200, envelope(
                    f"<wsnt:RenewResponse><wsnt:TerminationTime>{self._termination(ttl)}"
                    f"</wsnt:TerminationTime><wsnt:CurrentTime>{iso(now)}</wsnt:CurrentTime></wsnt:RenewResponse>"
                )
            if op == "Unsubscribe":
                del self.state.subscriptions[sub.id]
                return 200, envelope("<wsnt:UnsubscribeResponse/>")
            raise action_not_supported(op)
        except SoapFault as fault:
            return fault.http_status, fault_envelope(fault)

    def _termination(self, ttl: float) -> str:
        return iso(self.state.now() + timedelta(seconds=ttl))

    # ---- Device ---------------------------------------------------------------------------------------

    def get_system_date_and_time(self, _: Element) -> str:
        now = self.state.now()
        return (
            "<tds:GetSystemDateAndTimeResponse><tds:SystemDateAndTime>"
            f"<tt:DateTimeType>{self.state.date_time_type}</tt:DateTimeType>"
            f"<tt:DaylightSavings>{str(self.state.daylight_savings).lower()}</tt:DaylightSavings>"
            f"<tt:TimeZone><tt:TZ>{esc(self.state.timezone)}</tt:TZ></tt:TimeZone>"
            "<tt:UTCDateTime>"
            f"<tt:Time><tt:Hour>{now.hour}</tt:Hour><tt:Minute>{now.minute}</tt:Minute>"
            f"<tt:Second>{now.second}</tt:Second></tt:Time>"
            f"<tt:Date><tt:Year>{now.year}</tt:Year><tt:Month>{now.month}</tt:Month><tt:Day>{now.day}</tt:Day></tt:Date>"
            "</tt:UTCDateTime></tds:SystemDateAndTime></tds:GetSystemDateAndTimeResponse>"
        )

    def set_system_date_and_time(self, req: Element) -> str:
        kind = txt(req, "{*}DateTimeType", "Manual") or "Manual"
        if kind not in ("Manual", "NTP"):
            raise invalid_arg("DateTimeType không hợp lệ")
        utc_el = req.find("{*}UTCDateTime")
        moment = _parse_utc(utc_el) if utc_el is not None else None
        if kind == "Manual" and utc_el is not None and moment is None:
            raise invalid_arg("UTCDateTime không hợp lệ")
        self.state.date_time_type = kind
        self.state.daylight_savings = (txt(req, "{*}DaylightSavings", "false") or "false").lower() == "true"
        self.state.timezone = txt(req, "{*}TimeZone/{*}TZ", self.state.timezone) or self.state.timezone
        if kind == "Manual" and moment is not None:
            # Đặt tay: đồng hồ giả lập chạy tiếp từ mốc được đặt
            self.state.clock_offset = float(round((moment - datetime.now(UTC)).total_seconds()))
        elif kind == "NTP" and self.config.ntp_sync and (self.state.ntp_servers or self.state.ntp_from_dhcp):
            self.state.clock_offset = 0.0
        return "<tds:SetSystemDateAndTimeResponse/>"

    def get_device_information(self, _: Element) -> str:
        c = self.config
        return (
            "<tds:GetDeviceInformationResponse>"
            f"<tds:Manufacturer>{esc(c.manufacturer)}</tds:Manufacturer><tds:Model>{esc(c.model)}</tds:Model>"
            f"<tds:FirmwareVersion>{esc(c.firmware)}</tds:FirmwareVersion>"
            f"<tds:SerialNumber>{esc(c.serial)}</tds:SerialNumber><tds:HardwareId>{esc(c.model)}</tds:HardwareId>"
            "</tds:GetDeviceInformationResponse>"
        )

    def _xaddr(self, service: str) -> str:
        return f"{self.config.base_url}/onvif/{service}"

    def get_services(self, _: Element) -> str:
        max_profiles = len(self.state.profiles)
        items = [
            (NS["tds"], "device_service", "<tds:Capabilities><tds:Capabilities/></tds:Capabilities>", (2, 60)),
            (
                NS["trt"],
                "media_service",
                '<tds:Capabilities><trt:Capabilities SnapshotUri="true" Rotation="false">'
                f'<trt:ProfileCapabilities MaximumNumberOfProfiles="{max_profiles}"/>'
                '<trt:StreamingCapabilities RTP_TCP="true" RTP_RTSP_TCP="true"/>'
                "</trt:Capabilities></tds:Capabilities>",
                (2, 60),
            ),
            (
                NS["timg"],
                "imaging_service",
                '<tds:Capabilities><timg:Capabilities ImageStabilization="false"/></tds:Capabilities>',
                (2, 60),
            ),
            (
                NS["tev"],
                "events_service",
                '<tds:Capabilities><tev:Capabilities WSSubscriptionPolicySupport="false" '
                'WSPullPointSupport="true" WSPausableSubscriptionManagerInterfaceSupport="false"/></tds:Capabilities>',
                (2, 60),
            ),
        ]
        if self.config.ptz:
            items.append(
                (
                    NS["tptz"],
                    "ptz_service",
                    '<tds:Capabilities><tptz:Capabilities EFlip="false" Reverse="false"/></tds:Capabilities>',
                    (2, 60),
                )
            )
        if self.config.zones:
            items.append(
                (
                    NS["tr2"],
                    "media2_service",
                    '<tds:Capabilities><tr2:Capabilities SnapshotUri="false" Rotation="false" '
                    'VideoSourceMode="false" OSD="false" Mask="true" SourceMask="false">'
                    f'<tr2:ProfileCapabilities MaximumNumberOfProfiles="{max_profiles}"/>'
                    '<tr2:StreamingCapabilities RTSPStreaming="true"/></tr2:Capabilities></tds:Capabilities>',
                    (2, 60),
                )
            )
            items.append(
                (
                    NS["tan"],
                    "analytics_service",
                    '<tds:Capabilities><tan:Capabilities RuleSupport="true" AnalyticsModuleSupport="true" '
                    'CellBasedSceneDescriptionSupported="true"/></tds:Capabilities>',
                    (2, 60),
                )
            )
        if self.edge is not None:
            items.append(
                (
                    NS["trc"],
                    "recording_service",
                    "<tds:Capabilities>"
                    f'<trc:Capabilities DynamicRecordings="false" MaxRecordings="{len(self.edge.recordings)}"/>'
                    "</tds:Capabilities>",
                    (2, 60),
                )
            )
            items.append(
                (
                    NS["tse"],
                    "search_service",
                    '<tds:Capabilities><tse:Capabilities MetadataSearch="false" GeneralStartEvents="true"/>'
                    "</tds:Capabilities>",
                    (2, 60),
                )
            )
            items.append(
                (
                    NS["trp"],
                    "replay_service",
                    '<tds:Capabilities><trp:Capabilities ReversePlayback="false" RTP_RTSP_TCP="true"/>'
                    "</tds:Capabilities>",
                    (2, 60),
                )
            )
        include = (txt(_, "{*}IncludeCapability", "false") or "false").lower() == "true"
        body = "".join(
            f"<tds:Service><tds:Namespace>{ns}</tds:Namespace><tds:XAddr>{esc(self._xaddr(path))}</tds:XAddr>"
            f"{caps if include else ''}{_version(*ver)}</tds:Service>"
            for ns, path, caps, ver in items
        )
        return f"<tds:GetServicesResponse>{body}</tds:GetServicesResponse>"

    def get_capabilities(self, _: Element) -> str:
        ptz = f"<tt:PTZ><tt:XAddr>{esc(self._xaddr('ptz_service'))}</tt:XAddr></tt:PTZ>" if self.config.ptz else ""
        analytics = (
            f"<tt:Analytics><tt:XAddr>{esc(self._xaddr('analytics_service'))}</tt:XAddr>"
            "<tt:RuleSupport>true</tt:RuleSupport><tt:AnalyticsModuleSupport>true</tt:AnalyticsModuleSupport>"
            "</tt:Analytics>"
            if self.config.zones
            else ""
        )
        recorder = (
            f"<tt:Extension><tt:Recording><tt:XAddr>{esc(self._xaddr('recording_service'))}</tt:XAddr>"
            "<tt:ReceiverSource>false</tt:ReceiverSource><tt:MediaProfileSource>true</tt:MediaProfileSource>"
            "<tt:DynamicRecordings>false</tt:DynamicRecordings><tt:DynamicTracks>false</tt:DynamicTracks>"
            "<tt:MaxStringLength>64</tt:MaxStringLength></tt:Recording>"
            f"<tt:Search><tt:XAddr>{esc(self._xaddr('search_service'))}</tt:XAddr>"
            "<tt:MetadataSearch>false</tt:MetadataSearch></tt:Search>"
            f"<tt:Replay><tt:XAddr>{esc(self._xaddr('replay_service'))}</tt:XAddr></tt:Replay></tt:Extension>"
            if self.edge is not None
            else ""
        )
        return (
            f"<tds:GetCapabilitiesResponse><tds:Capabilities>{analytics}"
            f"<tt:Device><tt:XAddr>{esc(self._xaddr('device_service'))}</tt:XAddr></tt:Device>"
            f"<tt:Events><tt:XAddr>{esc(self._xaddr('events_service'))}</tt:XAddr>"
            "<tt:WSSubscriptionPolicySupport>false</tt:WSSubscriptionPolicySupport>"
            "<tt:WSPullPointSupport>true</tt:WSPullPointSupport>"
            "<tt:WSPausableSubscriptionManagerInterfaceSupport>false</tt:WSPausableSubscriptionManagerInterfaceSupport>"
            "</tt:Events>"
            f"<tt:Imaging><tt:XAddr>{esc(self._xaddr('imaging_service'))}</tt:XAddr></tt:Imaging>"
            f"<tt:Media><tt:XAddr>{esc(self._xaddr('media_service'))}</tt:XAddr>"
            "<tt:StreamingCapabilities><tt:RTPMulticast>false</tt:RTPMulticast><tt:RTP_TCP>true</tt:RTP_TCP>"
            "<tt:RTP_RTSP_TCP>true</tt:RTP_RTSP_TCP></tt:StreamingCapabilities></tt:Media>"
            f"{ptz}{recorder}</tds:Capabilities></tds:GetCapabilitiesResponse>"
        )

    def get_network_interfaces(self, _: Element) -> str:
        st = self.state
        host = st.ipv4_address or self.config.advertise_host
        address = f"<tt:Address>{esc(host)}</tt:Address><tt:PrefixLength>{st.prefix_length}</tt:PrefixLength>"
        config = (
            f"<tt:FromDHCP>{address}</tt:FromDHCP><tt:DHCP>true</tt:DHCP>"
            if st.dhcp
            else f"<tt:Manual>{address}</tt:Manual><tt:DHCP>false</tt:DHCP>"
        )
        return (
            '<tds:GetNetworkInterfacesResponse><tds:NetworkInterfaces token="eth0">'
            "<tt:Enabled>true</tt:Enabled>"
            f"<tt:Info><tt:Name>eth0</tt:Name><tt:HwAddress>{esc(self.config.mac)}</tt:HwAddress>"
            "<tt:MTU>1500</tt:MTU></tt:Info>"
            f"<tt:IPv4><tt:Enabled>true</tt:Enabled><tt:Config>{config}</tt:Config></tt:IPv4>"
            "</tds:NetworkInterfaces></tds:GetNetworkInterfacesResponse>"
        )

    def set_network_interfaces(self, req: Element) -> str:
        """Chỉ ghi nhận cấu hình vào bộ nhớ, không đổi IP thật; đổi DHCP ↔ tĩnh thì báo cần khởi động lại."""
        if txt(req, "{*}InterfaceToken") != "eth0":
            raise invalid_arg("InterfaceToken không tồn tại")
        st = self.state
        ipv4 = req.find("{*}NetworkInterface/{*}IPv4")
        dhcp = st.dhcp
        address, prefix = st.ipv4_address, st.prefix_length
        if ipv4 is not None:
            dhcp = (txt(ipv4, "{*}DHCP", str(st.dhcp)) or "false").lower() == "true"
            if dhcp:
                # DHCP "cấp" lại đúng địa chỉ thật của container
                address = None
            else:
                try:
                    address = str(ipaddress.IPv4Address(txt(ipv4, "{*}Manual/{*}Address") or ""))
                    prefix = int(txt(ipv4, "{*}Manual/{*}PrefixLength", "24") or "24")
                except ValueError as e:
                    raise invalid_arg("Địa chỉ IPv4 tĩnh không hợp lệ") from e
                if not 1 <= prefix <= 32:
                    raise invalid_arg("PrefixLength không hợp lệ")
        reboot = dhcp != st.dhcp
        st.dhcp, st.ipv4_address, st.prefix_length = dhcp, address, prefix
        log.info("Giả lập đổi mạng: %s", "DHCP" if dhcp else f"{address}/{prefix}")
        return (
            "<tds:SetNetworkInterfacesResponse>"
            f"<tds:RebootNeeded>{str(reboot).lower()}</tds:RebootNeeded></tds:SetNetworkInterfacesResponse>"
        )

    def get_network_default_gateway(self, _: Element) -> str:
        gateway = self.state.default_gateway
        item = f"<tt:IPv4Address>{esc(gateway)}</tt:IPv4Address>" if gateway else ""
        return (
            "<tds:GetNetworkDefaultGatewayResponse><tds:NetworkGateway>"
            f"{item}</tds:NetworkGateway></tds:GetNetworkDefaultGatewayResponse>"
        )

    def set_network_default_gateway(self, req: Element) -> str:
        raw = txt(req, "{*}IPv4Address")
        try:
            self.state.default_gateway = str(ipaddress.IPv4Address(raw)) if raw else None
        except ValueError as e:
            raise invalid_arg("Gateway IPv4 không hợp lệ") from e
        return "<tds:SetNetworkDefaultGatewayResponse/>"

    def get_dns(self, _: Element) -> str:
        items = "".join(
            f"<tt:DNSManual><tt:Type>IPv4</tt:Type><tt:IPv4Address>{esc(s)}</tt:IPv4Address></tt:DNSManual>"
            for s in self.state.dns_servers
        )
        return (
            "<tds:GetDNSResponse><tds:DNSInformation><tt:FromDHCP>false</tt:FromDHCP>"
            f"{items}</tds:DNSInformation></tds:GetDNSResponse>"
        )

    def set_dns(self, req: Element) -> str:
        servers = []
        for item in req.iterfind("{*}DNSManual"):
            try:
                servers.append(str(ipaddress.IPv4Address(txt(item, "{*}IPv4Address") or "")))
            except ValueError as e:
                raise invalid_arg("DNSManual không hợp lệ") from e
        self.state.dns_servers = servers
        return "<tds:SetDNSResponse/>"

    def get_hostname(self, _: Element) -> str:
        return (
            "<tds:GetHostnameResponse><tds:HostnameInformation><tt:FromDHCP>false</tt:FromDHCP>"
            f"<tt:Name>{esc(self.state.hostname)}</tt:Name></tds:HostnameInformation></tds:GetHostnameResponse>"
        )

    def get_ntp(self, _: Element) -> str:
        items = "".join(
            f"<tt:NTPManual><tt:Type>DNS</tt:Type><tt:DNSname>{esc(s)}</tt:DNSname></tt:NTPManual>"
            if not s.replace(".", "").isdigit()
            else f"<tt:NTPManual><tt:Type>IPv4</tt:Type><tt:IPv4Address>{esc(s)}</tt:IPv4Address></tt:NTPManual>"
            for s in self.state.ntp_servers
        )
        return (
            "<tds:GetNTPResponse><tds:NTPInformation>"
            f"<tt:FromDHCP>{str(self.state.ntp_from_dhcp).lower()}</tt:FromDHCP>{items}"
            "</tds:NTPInformation></tds:GetNTPResponse>"
        )

    def set_ntp(self, req: Element) -> str:
        servers = []
        for item in req.iterfind("{*}NTPManual"):
            value = txt(item, "{*}IPv4Address") or txt(item, "{*}IPv6Address") or txt(item, "{*}DNSname")
            if not value:
                raise invalid_arg("NTPManual thiếu địa chỉ")
            servers.append(value)
        from_dhcp = (txt(req, "{*}FromDHCP", "false") or "false").lower() == "true"
        if not from_dhcp and not servers:
            raise invalid_arg("Cần ít nhất một máy chủ NTP")
        self.state.ntp_from_dhcp, self.state.ntp_servers = from_dhcp, servers
        return "<tds:SetNTPResponse/>"

    def system_reboot(self, _: Element) -> str:
        self.state.reboots += 1
        log.info("Giả lập khởi động lại lần %d", self.state.reboots)
        return "<tds:SystemRebootResponse><tds:Message>Rebooting in 30 seconds</tds:Message></tds:SystemRebootResponse>"

    def get_users(self, _: Element) -> str:
        users = "".join(
            f"<tds:User><tt:Username>{esc(name)}</tt:Username><tt:UserLevel>{level}</tt:UserLevel></tds:User>"
            for name, (_pwd, level) in self.state.users.items()
        )
        return f"<tds:GetUsersResponse>{users}</tds:GetUsersResponse>"

    def set_user(self, req: Element) -> str:
        for user in req.iterfind("{*}User"):
            name = txt(user, "{*}Username", "") or ""
            if name not in self.state.users:
                raise SoapFault("Sender", "ter:UsernameMissing", f"Không có tài khoản {name}")
            password = txt(user, "{*}Password")
            level = txt(user, "{*}UserLevel", self.state.users[name][1]) or "User"
            if password is not None and len(password) < 4:
                raise SoapFault("Sender", "ter:PasswordTooWeak", "Mật khẩu quá ngắn")
            self.state.users[name] = (password if password is not None else self.state.users[name][0], level)
        return "<tds:SetUserResponse/>"

    # ---- Media ----------------------------------------------------------------------------------------

    def _encoder_xml(self, enc: Encoder) -> str:
        ext = f"<tt:Extension><sim:BFrames>{enc.b_frames}</sim:BFrames></tt:Extension>" if enc.b_frames else ""
        return (
            f"<tt:Name>{esc(enc.name)}</tt:Name><tt:UseCount>1</tt:UseCount><tt:Encoding>{enc.encoding}</tt:Encoding>"
            f"<tt:Resolution><tt:Width>{enc.width}</tt:Width><tt:Height>{enc.height}</tt:Height></tt:Resolution>"
            f"<tt:Quality>{enc.quality:g}</tt:Quality>"
            f"<tt:RateControl><tt:FrameRateLimit>{enc.fps}</tt:FrameRateLimit>"
            f"<tt:EncodingInterval>1</tt:EncodingInterval><tt:BitrateLimit>{enc.bitrate}</tt:BitrateLimit>"
            "</tt:RateControl>"
            f"<tt:H264><tt:GovLength>{enc.gov}</tt:GovLength><tt:H264Profile>{enc.h264_profile}</tt:H264Profile>"
            "</tt:H264>"
            "<tt:Multicast><tt:Address><tt:Type>IPv4</tt:Type><tt:IPv4Address>0.0.0.0</tt:IPv4Address></tt:Address>"
            "<tt:Port>0</tt:Port><tt:TTL>5</tt:TTL><tt:AutoStart>false</tt:AutoStart></tt:Multicast>"
            f"<tt:SessionTimeout>PT60S</tt:SessionTimeout>{ext}"
        )

    def _profile_xml(self, p: Profile) -> str:
        audio = (
            '<tt:AudioSourceConfiguration token="asc0"><tt:Name>Audio</tt:Name><tt:UseCount>1</tt:UseCount>'
            "<tt:SourceToken>as0</tt:SourceToken></tt:AudioSourceConfiguration>"
            if p.audio
            else ""
        )
        analytics = (
            '<tt:VideoAnalyticsConfiguration token="vac0"><tt:Name>Analytics</tt:Name><tt:UseCount>2</tt:UseCount>'
            "<tt:AnalyticsEngineConfiguration/><tt:RuleEngineConfiguration/></tt:VideoAnalyticsConfiguration>"
            if self.config.zones
            else ""
        )
        ptz = (
            '<tt:PTZConfiguration token="ptz0"><tt:Name>PTZ</tt:Name><tt:UseCount>2</tt:UseCount>'
            "<tt:NodeToken>ptznode0</tt:NodeToken></tt:PTZConfiguration>"
            if self.config.ptz
            else ""
        )
        return (
            f'<trt:Profiles token={attr(p.token)} fixed="true"><tt:Name>{esc(p.name)}</tt:Name>'
            f"<tt:VideoSourceConfiguration token={attr(p.config_token)}><tt:Name>{esc(p.config_name)}</tt:Name>"
            f"<tt:UseCount>2</tt:UseCount><tt:SourceToken>{esc(p.source_token)}</tt:SourceToken>"
            '<tt:Bounds x="0" y="0" width="1920" height="1080"/>'
            f"</tt:VideoSourceConfiguration>{audio}"
            f"<tt:VideoEncoderConfiguration token={attr(p.encoder.token)}>{self._encoder_xml(p.encoder)}"
            f"</tt:VideoEncoderConfiguration>{analytics}{ptz}</trt:Profiles>"
        )

    def _require_profile(self, req: Element) -> Profile:
        profile = self.state.profile(txt(req, "{*}ProfileToken"))
        if profile is None:
            raise SoapFault("Sender", "ter:NoProfile", "Profile không tồn tại")
        return profile

    TALK_PROFILE = "profile_talk"

    def _talk_profile_xml(self) -> str:
        """Profile chỉ có đầu ra âm thanh (loa) cho backchannel, không có video (US-15)."""
        return (
            f'<trt:Profiles token="{self.TALK_PROFILE}" fixed="true"><tt:Name>Talk</tt:Name>'
            '<tt:AudioOutputConfiguration token="aoc0"><tt:Name>Speaker</tt:Name><tt:UseCount>1</tt:UseCount>'
            "<tt:OutputToken>ao0</tt:OutputToken><tt:SendPrimacy>www.onvif.org/ver20/HalfDuplex/Client</tt:SendPrimacy>"
            "<tt:OutputLevel>80</tt:OutputLevel></tt:AudioOutputConfiguration>"
            '<tt:AudioDecoderConfiguration token="adc0"><tt:Name>G711</tt:Name><tt:UseCount>1</tt:UseCount>'
            "</tt:AudioDecoderConfiguration></trt:Profiles>"
        )

    def get_profiles(self, _: Element) -> str:
        body = "".join(self._profile_xml(p) for p in self.state.profiles)
        if self.config.talk_port:
            body += self._talk_profile_xml()
        return f"<trt:GetProfilesResponse>{body}</trt:GetProfilesResponse>"

    def get_video_sources(self, _: Element) -> str:
        items = []
        for src in self.state.sources:
            fps, width, height = (src.framerate, src.width, src.height) if src.signal else (0, 0, 0)
            items.append(
                f"<trt:VideoSources token={attr(src.token)}><tt:Framerate>{fps}</tt:Framerate>"
                f"<tt:Resolution><tt:Width>{width}</tt:Width><tt:Height>{height}</tt:Height></tt:Resolution>"
                "</trt:VideoSources>"
            )
        return f"<trt:GetVideoSourcesResponse>{''.join(items)}</trt:GetVideoSourcesResponse>"

    def get_stream_uri(self, req: Element) -> str:
        if self.config.talk_port and txt(req, "{*}ProfileToken") == self.TALK_PROFILE:
            uri = self.config.talk_uri
        else:
            uri = self._require_profile(req).rtsp
        protocol = txt(req, "{*}StreamSetup/{*}Transport/{*}Protocol", "RTSP")
        if protocol not in ("RTSP", "UDP", "TCP"):
            raise invalid_arg("Giao thức không hỗ trợ")
        return (
            f"<trt:GetStreamUriResponse><trt:MediaUri><tt:Uri>{esc(uri)}</tt:Uri>"
            "<tt:InvalidAfterConnect>false</tt:InvalidAfterConnect><tt:InvalidAfterReboot>false</tt:InvalidAfterReboot>"
            "<tt:Timeout>PT0S</tt:Timeout></trt:MediaUri></trt:GetStreamUriResponse>"
        )

    def get_snapshot_uri(self, req: Element) -> str:
        profile = self._require_profile(req)
        uri = f"{self.config.base_url}/onvif/snapshot/{profile.token}"
        return (
            f"<trt:GetSnapshotUriResponse><trt:MediaUri><tt:Uri>{esc(uri)}</tt:Uri>"
            "<tt:InvalidAfterConnect>false</tt:InvalidAfterConnect><tt:InvalidAfterReboot>false</tt:InvalidAfterReboot>"
            "<tt:Timeout>PT0S</tt:Timeout></trt:MediaUri></trt:GetSnapshotUriResponse>"
        )

    def get_video_encoder_configurations(self, _: Element) -> str:
        body = "".join(
            f"<trt:Configurations token={attr(p.encoder.token)}>{self._encoder_xml(p.encoder)}</trt:Configurations>"
            for p in self.state.profiles
        )
        return f"<trt:GetVideoEncoderConfigurationsResponse>{body}</trt:GetVideoEncoderConfigurationsResponse>"

    def _require_encoder(self, token: str | None) -> Encoder:
        enc = self.state.encoder(token)
        if enc is None:
            raise SoapFault("Sender", "ter:NoConfig", "Cấu hình bộ mã hóa không tồn tại")
        return enc

    def get_video_encoder_configuration(self, req: Element) -> str:
        enc = self._require_encoder(txt(req, "{*}ConfigurationToken"))
        return (
            "<trt:GetVideoEncoderConfigurationResponse>"
            f"<trt:Configuration token={attr(enc.token)}>{self._encoder_xml(enc)}</trt:Configuration>"
            "</trt:GetVideoEncoderConfigurationResponse>"
        )

    def get_video_encoder_options(self, req: Element) -> str:
        enc = self._require_encoder(txt(req, "{*}ConfigurationToken"))
        res = "".join(
            f"<tt:ResolutionsAvailable><tt:Width>{w}</tt:Width><tt:Height>{h}</tt:Height></tt:ResolutionsAvailable>"
            for w, h in enc.resolutions
        )
        h264 = (
            f"<tt:H264>{res}{_range('GovLengthRange', 1, 150)}{_range('FrameRateRange', 1, enc.max_fps)}"
            f"{_range('EncodingIntervalRange', 1, 1)}<tt:H264ProfilesSupported>Baseline</tt:H264ProfilesSupported>"
            "<tt:H264ProfilesSupported>Main</tt:H264ProfilesSupported>"
            "<tt:H264ProfilesSupported>High</tt:H264ProfilesSupported></tt:H264>"
        )
        jpeg = (
            f"<tt:JPEG>{res}{_range('FrameRateRange', 1, enc.max_fps)}{_range('EncodingIntervalRange', 1, 1)}</tt:JPEG>"
        )
        ext = (
            f"<tt:Extension><tt:H264>{res}{_range('GovLengthRange', 1, 150)}"
            f"{_range('FrameRateRange', 1, enc.max_fps)}{_range('BitrateRange', *enc.bitrate_range)}</tt:H264>"
            f"<sim:H265>{res}{_range('BitrateRange', *enc.bitrate_range)}</sim:H265></tt:Extension>"
        )
        return (
            "<trt:GetVideoEncoderConfigurationOptionsResponse><trt:Options>"
            f"{_range('QualityRange', 1, 6)}{jpeg}{h264}{ext}"
            "</trt:Options></trt:GetVideoEncoderConfigurationOptionsResponse>"
        )

    def set_video_encoder_configuration(self, req: Element) -> str:
        cfg = req.find("{*}Configuration")
        if cfg is None:
            raise invalid_arg("Thiếu Configuration")
        enc = self._require_encoder(cfg.get("token"))
        encoding = txt(cfg, "{*}Encoding", enc.encoding) or enc.encoding
        width = int(_float(txt(cfg, "{*}Resolution/{*}Width", str(enc.width)), "Width"))
        height = int(_float(txt(cfg, "{*}Resolution/{*}Height", str(enc.height)), "Height"))
        fps = int(_float(txt(cfg, "{*}RateControl/{*}FrameRateLimit", str(enc.fps)), "FrameRateLimit"))
        bitrate = int(_float(txt(cfg, "{*}RateControl/{*}BitrateLimit", str(enc.bitrate)), "BitrateLimit"))
        gov = int(_float(txt(cfg, "{*}H264/{*}GovLength", str(enc.gov)), "GovLength"))
        if encoding not in ("H264", "H265", "JPEG"):
            raise SoapFault("Sender", "ter:ConfigModify", f"Chuẩn nén {encoding} không hỗ trợ")
        if (width, height) not in enc.resolutions:
            raise SoapFault("Sender", "ter:ConfigModify", f"Độ phân giải {width}x{height} không hỗ trợ")
        if not 1 <= fps <= enc.max_fps:
            raise SoapFault("Sender", "ter:ConfigModify", "Tốc độ khung hình ngoài phạm vi")
        low, high = enc.bitrate_range
        if not low <= bitrate <= high:
            raise SoapFault("Sender", "ter:ConfigModify", "Bitrate ngoài phạm vi")
        if not 1 <= gov <= 150:
            raise SoapFault("Sender", "ter:ConfigModify", "GOP ngoài phạm vi")
        enc.encoding, enc.width, enc.height, enc.fps, enc.bitrate, enc.gov = encoding, width, height, fps, bitrate, gov
        enc.h264_profile = txt(cfg, "{*}H264/{*}H264Profile", enc.h264_profile) or enc.h264_profile
        return "<trt:SetVideoEncoderConfigurationResponse/>"

    # ---- PTZ ------------------------------------------------------------------------------------------

    def continuous_move(self, req: Element) -> str:
        self._require_profile(req)
        pan_tilt = req.find("{*}Velocity/{*}PanTilt")
        zoom_el = req.find("{*}Velocity/{*}Zoom")
        pan = _float(pan_tilt.get("x", "0"), "x") if pan_tilt is not None else 0.0
        tilt = _float(pan_tilt.get("y", "0"), "y") if pan_tilt is not None else 0.0
        zoom = _float(zoom_el.get("x", "0"), "zoom") if zoom_el is not None else 0.0
        if any(abs(v) > 1 for v in (pan, tilt, zoom)):
            raise invalid_arg("Vận tốc ngoài phạm vi [-1, 1]")
        self.state.ptz_velocity = (pan, tilt, zoom)
        self.state.ptz_moves += 1
        return "<tptz:ContinuousMoveResponse/>"

    def ptz_stop(self, req: Element) -> str:
        self._require_profile(req)
        self.state.ptz_velocity = (0.0, 0.0, 0.0)
        return "<tptz:StopResponse/>"

    def get_presets(self, req: Element) -> str:
        self._require_profile(req)
        items = "".join(
            f"<tptz:Preset token={attr(token)}><tt:Name>{esc(name)}</tt:Name></tptz:Preset>"
            for token, name in self.state.presets.items()
        )
        return f"<tptz:GetPresetsResponse>{items}</tptz:GetPresetsResponse>"

    def _require_preset(self, req: Element) -> str:
        token = txt(req, "{*}PresetToken", "") or ""
        if token not in self.state.presets:
            raise SoapFault("Sender", "ter:NoToken", "Preset không tồn tại")
        return token

    def goto_preset(self, req: Element) -> str:
        self._require_profile(req)
        self._require_preset(req)
        return "<tptz:GotoPresetResponse/>"

    def set_preset(self, req: Element) -> str:
        self._require_profile(req)
        name = txt(req, "{*}PresetName", "") or ""
        if not name:
            raise invalid_arg("Thiếu tên preset")
        token = txt(req, "{*}PresetToken")
        if token:
            self._require_preset(req)
        else:
            if len(self.state.presets) >= 32:
                raise SoapFault("Receiver", "ter:TooManyPresets", "Đã đạt số preset tối đa")
            token = f"preset_{self.state.next_preset}"
            self.state.next_preset += 1
        self.state.presets[token] = name
        return f"<tptz:SetPresetResponse><tptz:PresetToken>{esc(token)}</tptz:PresetToken></tptz:SetPresetResponse>"

    def remove_preset(self, req: Element) -> str:
        self._require_profile(req)
        del self.state.presets[self._require_preset(req)]
        return "<tptz:RemovePresetResponse/>"

    # ---- Imaging --------------------------------------------------------------------------------------

    def _require_source(self, req: Element) -> None:
        if self.state.source(txt(req, "{*}VideoSourceToken")) is None:
            raise SoapFault("Sender", "ter:NoSource", "Nguồn video không tồn tại")

    def get_imaging_settings(self, req: Element) -> str:
        self._require_source(req)
        img = self.state.imaging
        return (
            "<timg:GetImagingSettingsResponse><timg:ImagingSettings>"
            f"<tt:Brightness>{img.brightness:g}</tt:Brightness><tt:ColorSaturation>50</tt:ColorSaturation>"
            f"<tt:Contrast>{img.contrast:g}</tt:Contrast><tt:IrCutFilter>{img.ir_cut}</tt:IrCutFilter>"
            "<tt:Sharpness>50</tt:Sharpness>"
            f"<tt:WideDynamicRange><tt:Mode>{img.wdr}</tt:Mode><tt:Level>50</tt:Level></tt:WideDynamicRange>"
            "</timg:ImagingSettings></timg:GetImagingSettingsResponse>"
        )

    def set_imaging_settings(self, req: Element) -> str:
        self._require_source(req)
        settings = req.find("{*}ImagingSettings")
        img = self.state.imaging
        updates: dict[str, float | str] = {}
        for field in ("Brightness", "Contrast"):
            raw = txt(settings, "{*}" + field)
            if raw is not None:
                value = _float(raw, field)
                if not 0 <= value <= 100:
                    raise invalid_arg(f"{field} ngoài phạm vi 0–100")
                updates[field] = value
        ir = txt(settings, "{*}IrCutFilter")
        if ir is not None and ir not in ("ON", "OFF", "AUTO"):
            raise invalid_arg("IrCutFilter không hợp lệ")
        wdr = txt(settings, "{*}WideDynamicRange/{*}Mode")
        if wdr is not None and wdr not in ("ON", "OFF"):
            raise invalid_arg("WideDynamicRange không hợp lệ")
        img.brightness = float(updates.get("Brightness", img.brightness))
        img.contrast = float(updates.get("Contrast", img.contrast))
        img.ir_cut = ir or img.ir_cut
        img.wdr = wdr or img.wdr
        return "<timg:SetImagingSettingsResponse/>"

    def get_imaging_options(self, req: Element) -> str:
        self._require_source(req)
        return (
            "<timg:GetOptionsResponse><timg:ImagingOptions>"
            f"{_range('Brightness', 0, 100)}{_range('ColorSaturation', 0, 100)}{_range('Contrast', 0, 100)}"
            "<tt:IrCutFilterModes>ON</tt:IrCutFilterModes><tt:IrCutFilterModes>OFF</tt:IrCutFilterModes>"
            "<tt:IrCutFilterModes>AUTO</tt:IrCutFilterModes>"
            f"{_range('Sharpness', 0, 100)}"
            "<tt:WideDynamicRange><tt:Mode>OFF</tt:Mode><tt:Mode>ON</tt:Mode>"
            f"{_range('Level', 0, 100)}</tt:WideDynamicRange>"
            "</timg:ImagingOptions></timg:GetOptionsResponse>"
        )

    # ---- Analytics: vùng phát hiện chuyển động (US-26) -----------------------------------------------

    @staticmethod
    def _require_vac(req: Element) -> None:
        if txt(req, "{*}ConfigurationToken") != "vac0":
            raise SoapFault("Sender", "ter:NoConfig", "Cấu hình phân tích video không tồn tại")

    def get_analytics_capabilities(self, _: Element) -> str:
        return (
            '<tan:GetServiceCapabilitiesResponse><tan:Capabilities RuleSupport="true" '
            'AnalyticsModuleSupport="true" CellBasedSceneDescriptionSupported="true"/>'
            "</tan:GetServiceCapabilitiesResponse>"
        )

    def get_supported_rules(self, req: Element) -> str:
        self._require_vac(req)
        cell = (
            f'<tt:RuleDescription Name="tt:CellMotionDetector" maxInstances="{MAX_CELL_RULES}"><tt:Parameters>'
            '<tt:SimpleItemDescription Name="MinCount" Type="xs:integer"/>'
            '<tt:SimpleItemDescription Name="AlarmOnDelay" Type="xs:integer"/>'
            '<tt:SimpleItemDescription Name="AlarmOffDelay" Type="xs:integer"/>'
            '<tt:SimpleItemDescription Name="ActiveCells" Type="xs:base64Binary"/>'
            "</tt:Parameters></tt:RuleDescription>"
        )
        region = (
            f'<tt:RuleDescription Name="tt:MotionRegionDetector" maxInstances="{MAX_REGION_RULES}"><tt:Parameters>'
            '<tt:SimpleItemDescription Name="Sensitivity" Type="xs:integer"/>'
            '<tt:ElementItemDescription Name="MotionRegion" Type="axt:MotionRegionConfig"/>'
            "</tt:Parameters></tt:RuleDescription>"
        )
        return (
            "<tan:GetSupportedRulesResponse><tan:SupportedRules>"
            "<tt:RuleContentSchemaLocation>http://www.onvif.org/ver10/schema/onvif.xsd</tt:RuleContentSchemaLocation>"
            f"{cell}{region}</tan:SupportedRules></tan:GetSupportedRulesResponse>"
        )

    @staticmethod
    def _rule_xml(rule: Rule) -> str:
        items = "".join(f"<tt:SimpleItem Name={attr(k)} Value={attr(v)}/>" for k, v in rule.items.items())
        region = ""
        if rule.type == "MotionRegionDetector":
            points = "".join(f"<tt:Point x={attr(f'{x:g}')} y={attr(f'{y:g}')}/>" for x, y in rule.polygon)
            region = (
                '<tt:ElementItem Name="MotionRegion"><axt:MotionRegion Armed="true" Mask="false">'
                f"<axt:Polygon>{points}</axt:Polygon></axt:MotionRegion></tt:ElementItem>"
            )
        return (
            f'<tan:Rule Name={attr(rule.name)} Type="tt:{rule.type}"><tt:Parameters>{items}{region}'
            "</tt:Parameters></tan:Rule>"
        )

    def get_rules(self, req: Element) -> str:
        self._require_vac(req)
        body = "".join(self._rule_xml(r) for r in self.state.rules.values())
        return f"<tan:GetRulesResponse>{body}</tan:GetRulesResponse>"

    @staticmethod
    def _parse_points(parent: Element | None, max_points: int) -> list[tuple[float, float]]:
        points = []
        for p in parent.iter() if parent is not None else []:
            if local(p.tag) != "Point":
                continue
            x, y = _float(p.get("x"), "x"), _float(p.get("y"), "y")
            if not (-1 <= x <= 1 and -1 <= y <= 1):
                raise invalid_arg("Tọa độ điểm ngoài phạm vi [-1, 1]")
            points.append((x, y))
        if not 3 <= len(points) <= max_points:
            raise invalid_arg(f"Đa giác cần từ 3 đến {max_points} điểm")
        return points

    def _parse_rule(self, el: Element) -> Rule:
        name = el.get("Name", "")
        kind = el.get("Type", "").rsplit(":", 1)[-1]
        if not name:
            raise invalid_arg("Quy tắc thiếu Name")
        if kind not in ("CellMotionDetector", "MotionRegionDetector"):
            raise SoapFault("Sender", "ter:InvalidRule", f"Loại quy tắc {kind} không hỗ trợ")
        items = {
            item.get("Name", ""): item.get("Value", "")
            for item in el.iterfind("{*}Parameters/{*}SimpleItem")
            if item.get("Name")
        }
        rule = Rule(name, kind, items)
        if kind == "CellMotionDetector":
            try:
                decode_active_cells(items.get("ActiveCells", ""), GRID_COLUMNS * GRID_ROWS)
            except ValueError as e:
                raise invalid_arg(f"ActiveCells không hợp lệ: {e}") from e
        else:
            sens = items.get("Sensitivity")
            if sens is not None and not (sens.isdigit() and 0 <= int(sens) <= 100):
                raise invalid_arg("Sensitivity ngoài phạm vi 0–100")
            region = next(
                (i for i in el.iterfind("{*}Parameters/{*}ElementItem") if i.get("Name") == "MotionRegion"), None
            )
            rule.polygon = self._parse_points(region, 16)
        return rule

    def _check_rule_limits(self, rules: dict[str, Rule]) -> None:
        for kind, limit in (("CellMotionDetector", MAX_CELL_RULES), ("MotionRegionDetector", MAX_REGION_RULES)):
            if sum(1 for r in rules.values() if r.type == kind) > limit:
                raise SoapFault("Receiver", "ter:TooManyRules", f"Vượt số quy tắc {kind} tối đa ({limit})")

    def create_rules(self, req: Element) -> str:
        self._require_vac(req)
        rules = dict(self.state.rules)
        for el in req.iterfind("{*}Rule"):
            rule = self._parse_rule(el)
            if rule.name in rules:
                raise SoapFault("Sender", "ter:RuleAlreadyExistent", f"Quy tắc {rule.name} đã tồn tại")
            rules[rule.name] = rule
        self._check_rule_limits(rules)
        self.state.rules = rules
        return "<tan:CreateRulesResponse/>"

    def modify_rules(self, req: Element) -> str:
        self._require_vac(req)
        rules = dict(self.state.rules)
        for el in req.iterfind("{*}Rule"):
            rule = self._parse_rule(el)
            current = rules.get(rule.name)
            if current is None:
                raise SoapFault("Sender", "ter:RuleNotExistent", f"Quy tắc {rule.name} không tồn tại")
            if current.type != rule.type:
                raise invalid_arg("Không được đổi loại quy tắc")
            rules[rule.name] = rule
        self.state.rules = rules
        return "<tan:ModifyRulesResponse/>"

    def delete_rules(self, req: Element) -> str:
        self._require_vac(req)
        names = [n.text.strip() for n in req.iterfind("{*}RuleName") if n.text]
        missing = [n for n in names if n not in self.state.rules]
        if missing:
            raise SoapFault("Sender", "ter:RuleNotExistent", f"Quy tắc {missing[0]} không tồn tại")
        for name in names:
            del self.state.rules[name]
        return "<tan:DeleteRulesResponse/>"

    def get_analytics_modules(self, req: Element) -> str:
        self._require_vac(req)
        return (
            '<tan:GetAnalyticsModulesResponse><tan:AnalyticsModule Name="MyCellMotionModule" '
            'Type="tt:CellMotionEngine"><tt:Parameters>'
            f'<tt:SimpleItem Name="Sensitivity" Value="{self.state.cell_sensitivity}"/>'
            f'<tt:ElementItem Name="Layout"><tt:CellLayout Columns="{GRID_COLUMNS}" Rows="{GRID_ROWS}">'
            f'<tt:Transformation><tt:Translate x="-1.0" y="-1.0"/>'
            f'<tt:Scale x="{2 / GRID_COLUMNS:.5f}" y="{2 / GRID_ROWS:.5f}"/></tt:Transformation>'
            "</tt:CellLayout></tt:ElementItem></tt:Parameters></tan:AnalyticsModule>"
            "</tan:GetAnalyticsModulesResponse>"
        )

    def modify_analytics_modules(self, req: Element) -> str:
        self._require_vac(req)
        sensitivity = self.state.cell_sensitivity
        for module in req.iterfind("{*}AnalyticsModule"):
            if module.get("Name") != "MyCellMotionModule":
                raise SoapFault("Sender", "ter:NoAnalyticsModule", "Mô-đun phân tích không tồn tại")
            for item in module.iterfind("{*}Parameters/{*}SimpleItem"):
                if item.get("Name") == "Sensitivity":
                    raw = item.get("Value", "")
                    if not (raw.isdigit() and 0 <= int(raw) <= 100):
                        raise invalid_arg("Sensitivity ngoài phạm vi 0–100")
                    sensitivity = int(raw)
        self.state.cell_sensitivity = sensitivity
        return "<tan:ModifyAnalyticsModulesResponse/>"

    # ---- Media2: vùng che riêng tư (US-26) -----------------------------------------------------------

    def get_mask_options(self, req: Element) -> str:
        if txt(req, "{*}ConfigurationToken") != "vsc0":
            raise SoapFault("Sender", "ter:NoConfig", "Cấu hình nguồn video không tồn tại")
        return (
            "<tr2:GetMaskOptionsResponse>"
            f'<tr2:Options RectangleOnly="false" SingleColorOnly="true" MaxMasks="{MAX_MASKS}" '
            f'MaxPoints="{MAX_MASK_POINTS}"><tr2:Types>Color</tr2:Types></tr2:Options>'
            "</tr2:GetMaskOptionsResponse>"
        )

    @staticmethod
    def _mask_xml(mask: Mask) -> str:
        points = "".join(f"<tt:Point x={attr(f'{x:g}')} y={attr(f'{y:g}')}/>" for x, y in mask.points)
        return (
            f"<tr2:Masks token={attr(mask.token)}><tr2:ConfigurationToken>vsc0</tr2:ConfigurationToken>"
            f"<tr2:Polygon>{points}</tr2:Polygon><tr2:Type>{esc(mask.type)}</tr2:Type>"
            '<tr2:Color X="16" Y="128" Z="128"/>'
            f"<tr2:Enabled>{str(mask.enabled).lower()}</tr2:Enabled></tr2:Masks>"
        )

    def get_masks(self, req: Element) -> str:
        token = txt(req, "{*}Token")
        config = txt(req, "{*}ConfigurationToken")
        masks = [m for m in self.state.masks.values() if token is None or m.token == token]
        if config not in (None, "vsc0"):
            masks = []
        return f"<tr2:GetMasksResponse>{''.join(self._mask_xml(m) for m in masks)}</tr2:GetMasksResponse>"

    def _parse_mask(self, el: Element | None, token: str) -> Mask:
        if el is None:
            raise invalid_arg("Thiếu Mask")
        if txt(el, "{*}ConfigurationToken") != "vsc0":
            raise SoapFault("Sender", "ter:NoConfig", "Cấu hình nguồn video không tồn tại")
        kind = txt(el, "{*}Type", "Color") or "Color"
        if kind != "Color":
            raise SoapFault("Sender", "ter:ConfigModify", f"Kiểu che {kind} không hỗ trợ")
        enabled = (txt(el, "{*}Enabled", "true") or "true").lower() == "true"
        return Mask(token, self._parse_points(el.find("{*}Polygon"), MAX_MASK_POINTS), enabled, kind)

    def create_mask(self, req: Element) -> str:
        if len(self.state.masks) >= MAX_MASKS:
            raise SoapFault("Receiver", "ter:MaxMasks", f"Đã đạt số vùng che tối đa ({MAX_MASKS})")
        token = f"mask_{self.state.next_mask}"
        mask = self._parse_mask(req.find("{*}Mask"), token)
        self.state.next_mask += 1
        self.state.masks[token] = mask
        return f"<tr2:CreateMaskResponse><tr2:Token>{esc(token)}</tr2:Token></tr2:CreateMaskResponse>"

    def set_mask(self, req: Element) -> str:
        el = req.find("{*}Mask")
        token = el.get("token", "") if el is not None else ""
        if token not in self.state.masks:
            raise SoapFault("Sender", "ter:NoConfig", "Vùng che không tồn tại")
        self.state.masks[token] = self._parse_mask(el, token)
        return "<tr2:SetMaskResponse/>"

    def delete_mask(self, req: Element) -> str:
        token = txt(req, "{*}Token", "") or ""
        if token not in self.state.masks:
            raise SoapFault("Sender", "ter:NoConfig", "Vùng che không tồn tại")
        del self.state.masks[token]
        return "<tr2:DeleteMaskResponse/>"

    # ---- Events ---------------------------------------------------------------------------------------

    def get_event_properties(self, _: Element) -> str:
        return (
            "<tev:GetEventPropertiesResponse>"
            "<tev:TopicNamespaceLocation>http://www.onvif.org/onvif/ver10/topics/topicns.xml</tev:TopicNamespaceLocation>"
            "<wsnt:FixedTopicSet>true</wsnt:FixedTopicSet>"
            '<wstop:TopicSet xmlns:wstop="http://docs.oasis-open.org/wsn/t-1">'
            '<tns1:RuleEngine><CellMotionDetector><Motion wstop:topic="true"/></CellMotionDetector></tns1:RuleEngine>'
            '<tns1:VideoSource><MotionAlarm wstop:topic="true"/></tns1:VideoSource>'
            "</wstop:TopicSet>"
            "<wsnt:TopicExpressionDialect>http://www.onvif.org/ver10/tev/topicExpression/ConcreteSet"
            "</wsnt:TopicExpressionDialect>"
            "<tev:MessageContentFilterDialect>http://www.onvif.org/ver10/tev/messageContentFilter/ItemFilter"
            "</tev:MessageContentFilterDialect>"
            "<tev:MessageContentSchemaLocation>http://www.onvif.org/onvif/ver10/schema/onvif.xsd"
            "</tev:MessageContentSchemaLocation>"
            "</tev:GetEventPropertiesResponse>"
        )

    def create_pull_point(self, req: Element) -> str:
        ttl = parse_duration(txt(req, "{*}InitialTerminationTime"), 60.0)
        sub = self.state.new_subscription(ttl)
        # Như camera thật: thông điệp "Initialized" đầu tiên mang trạng thái hiện tại
        sub.queue.put_nowait(self._motion_message(self.state.motion, "Initialized"))
        address = f"{self.config.base_url}/onvif/subscription/{sub.id}"
        now = self.state.now()
        return (
            "<tev:CreatePullPointSubscriptionResponse><tev:SubscriptionReference>"
            f"<wsa:Address>{esc(address)}</wsa:Address></tev:SubscriptionReference>"
            f"<wsnt:CurrentTime>{iso(now)}</wsnt:CurrentTime>"
            f"<wsnt:TerminationTime>{self._termination(ttl)}</wsnt:TerminationTime>"
            "</tev:CreatePullPointSubscriptionResponse>"
        )

    def _motion_message(self, state: bool, operation: str) -> str:
        value = str(state).lower()
        if self.config.motion_topic == "alarm":
            topic, source, data = (
                ALARM_TOPIC,
                '<tt:SimpleItem Name="Source" Value="vs0"/>',
                f'<tt:SimpleItem Name="State" Value="{value}"/>',
            )
        else:
            topic, source, data = (
                CELL_TOPIC,
                '<tt:SimpleItem Name="VideoSourceConfigurationToken" Value="vsc0"/>'
                '<tt:SimpleItem Name="VideoAnalyticsConfigurationToken" Value="vac0"/>'
                '<tt:SimpleItem Name="Rule" Value="MyMotionDetectorRule"/>',
                f'<tt:SimpleItem Name="IsMotion" Value="{value}"/>',
            )
        return (
            "<wsnt:NotificationMessage>"
            f'<wsnt:Topic Dialect="http://www.onvif.org/ver10/tev/topicExpression/ConcreteSet">{topic}</wsnt:Topic>'
            f'<wsnt:Message><tt:Message UtcTime="{iso(self.state.now())}" PropertyOperation="{operation}">'
            f"<tt:Source>{source}</tt:Source><tt:Data>{data}</tt:Data></tt:Message></wsnt:Message>"
            "</wsnt:NotificationMessage>"
        )

    def trigger_motion(self, state: bool) -> int:
        self.state.motion = state
        self.state.expire_subscriptions()
        message = self._motion_message(state, "Changed")
        for sub in self.state.subscriptions.values():
            sub.queue.put_nowait(message)
        return len(self.state.subscriptions)

    async def pull_messages(self, sub_id: str, req: Element) -> str:
        sub = self.state.subscriptions[sub_id]
        timeout = min(parse_duration(txt(req, "{*}Timeout"), 10.0), MAX_PULL_SECONDS)
        try:
            limit = max(1, int(txt(req, "{*}MessageLimit", "10") or "10"))
        except ValueError as e:
            raise invalid_arg("MessageLimit không hợp lệ") from e
        messages: list[str] = []
        with contextlib.suppress(TimeoutError):
            messages.append(await asyncio.wait_for(sub.queue.get(), timeout))
        while len(messages) < limit and not sub.queue.empty():
            messages.append(sub.queue.get_nowait())
        now = self.state.now()
        remaining = max(0.0, sub.expires - time.monotonic())
        return (
            f"<tev:PullMessagesResponse><tev:CurrentTime>{iso(now)}</tev:CurrentTime>"
            f"<tev:TerminationTime>{self._termination(remaining)}</tev:TerminationTime>"
            f"{''.join(messages)}</tev:PullMessagesResponse>"
        )

    # ---- Ảnh chụp -------------------------------------------------------------------------------------

    def check_basic_auth(self, header: str | None) -> bool:
        if not header or not header.lower().startswith("basic "):
            return False
        try:
            user, _, password = base64.b64decode(header[6:].strip()).decode("utf-8").partition(":")
        except (binascii.Error, UnicodeDecodeError):
            return False
        expected = self.state.passwords().get(user)
        return expected is not None and hmac.compare_digest(expected, password)


class MotionRequest(BaseModel):
    state: bool


class SignalRequest(BaseModel):
    signal: bool


class ClockRequest(BaseModel):
    offsetSeconds: int  # noqa: N815 - khớp JSON camelCase của API /sim


def _parse_utc(el: Element) -> datetime | None:
    try:
        return datetime(
            int(txt(el, "{*}Date/{*}Year") or ""),
            int(txt(el, "{*}Date/{*}Month") or ""),
            int(txt(el, "{*}Date/{*}Day") or ""),
            int(txt(el, "{*}Time/{*}Hour") or ""),
            int(txt(el, "{*}Time/{*}Minute") or ""),
            int(txt(el, "{*}Time/{*}Second") or ""),
            tzinfo=UTC,
        )
    except ValueError:
        return None


def create_app(config: SimConfig | None = None) -> FastAPI:
    cfg = config or SimConfig.from_env()
    sim = OnvifSimulator(cfg)

    talk = BackchannelServer(sim.state.passwords, cfg.talk_port) if cfg.talk_port else None

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        if talk is not None:
            await talk.start()
        responder = None
        if cfg.discovery_port:
            responder = DiscoveryResponder(cfg, cfg.discovery_bind)
            try:
                await responder.start()
            except OSError as e:
                log.warning("Không mở được cổng WS-Discovery UDP %d: %s", cfg.discovery_port, e)
                responder = None
        log.info("ONVIF sim '%s' sẵn sàng tại %s", cfg.name, cfg.base_url)
        try:
            yield
        finally:
            if responder is not None:
                responder.stop()
            if talk is not None:
                await talk.stop()

    app = FastAPI(title="CamHub ONVIF Simulator", version=__version__, lifespan=lifespan)
    app.state.sim = sim

    def soap_response(result: tuple[int, bytes]) -> Response:
        return Response(content=result[1], status_code=result[0], media_type=SOAP_CONTENT_TYPE)

    @app.post("/onvif/subscription/{sub_id}")
    async def subscription(sub_id: str, request: Request) -> Response:
        return soap_response(await sim.handle_subscription(sub_id, await request.body()))

    @app.post("/onvif/{service}")
    async def onvif(service: str, request: Request) -> Response:
        return soap_response(await sim.handle(service, await request.body()))

    @app.get("/onvif/snapshot/{profile_token}")
    async def snapshot(profile_token: str, request: Request) -> Response:
        if not sim.check_basic_auth(request.headers.get("authorization")):
            return Response(status_code=401, headers={"WWW-Authenticate": 'Basic realm="onvif-sim"'})
        if sim.state.profile(profile_token) is None:
            return Response(status_code=404)
        return Response(content=SNAPSHOT_JPEG, media_type="image/jpeg")

    @app.post("/sim/motion")
    async def motion(body: MotionRequest) -> dict[str, object]:
        delivered = sim.trigger_motion(body.state)
        return {"state": body.state, "subscriptions": delivered}

    @app.post("/sim/channels/{channel}/signal", response_model=None)
    async def channel_signal(channel: int, body: SignalRequest) -> dict[str, object] | JSONResponse:
        source = next((s for s in sim.state.sources if s.channel == channel and channel > 0), None)
        if source is None:
            return JSONResponse({"detail": "Kênh không tồn tại"}, status_code=404)
        source.signal = body.signal
        return {"channel": channel, "signal": source.signal}

    @app.post("/sim/clock")
    async def clock(body: ClockRequest) -> dict[str, object]:
        """Đặt lệch đồng hồ camera (giây, dương là chạy nhanh) để kiểm tra cảnh báo lệch giờ (US-25)."""
        sim.state.clock_offset = float(body.offsetSeconds)
        return {"offsetSeconds": body.offsetSeconds, "deviceTime": iso(sim.state.now())}

    @app.get("/sim/talk", response_model=None)
    async def talk_stats() -> dict[str, object] | JSONResponse:
        """Số gói/byte âm thanh nhận được qua backchannel (US-15)."""
        if talk is None:
            return JSONResponse({"detail": "Camera không có loa (SIM_TALK_PORT=0)"}, status_code=404)
        return talk.stats.as_dict()

    @app.get("/sim/state")
    async def sim_state() -> dict[str, object]:
        st = sim.state
        return {
            "imaging": vars(st.imaging),
            "encoders": [
                {k: v for k, v in vars(p.encoder).items() if k not in ("resolutions", "bitrate_range")}
                for p in st.profiles
            ],
            "presets": st.presets,
            "ptzVelocity": st.ptz_velocity,
            "ptzMoves": st.ptz_moves,
            "ntpServers": st.ntp_servers,
            "dateTimeType": st.date_time_type,
            "timezone": st.timezone,
            "clockOffsetSeconds": st.clock_offset,
            "network": {
                "dhcp": st.dhcp,
                "address": st.ipv4_address or cfg.advertise_host,
                "prefixLength": st.prefix_length,
                "gateway": st.default_gateway,
                "dns": st.dns_servers,
            },
            "reboots": st.reboots,
            "motion": st.motion,
            "subscriptions": len(st.subscriptions),
            "users": list(st.users),
            "channels": [
                {"channel": s.channel, "videoSourceToken": s.token, "signal": s.signal}
                for s in st.sources
                if s.channel > 0
            ],
            "edgeRecordings": [
                {"recordingToken": r.token, "videoSourceToken": r.source_token, "replayUri": r.rtsp}
                for r in (sim.edge.recordings if sim.edge else [])
            ],
            "zones": {
                "grid": {"columns": GRID_COLUMNS, "rows": GRID_ROWS},
                "sensitivity": st.cell_sensitivity,
                "rules": {
                    name: {"type": r.type, "items": r.items, "polygon": r.polygon} for name, r in st.rules.items()
                },
                "masks": [vars(m) for m in st.masks.values()],
            },
        }

    @app.get("/health")
    async def health() -> dict[str, str]:
        return {"status": "UP", "name": cfg.name}

    return app
