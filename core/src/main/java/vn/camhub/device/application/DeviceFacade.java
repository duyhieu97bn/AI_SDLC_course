package vn.camhub.device.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.camhub.device.api.DeviceApi;
import vn.camhub.device.api.DeviceControlPort;
import vn.camhub.device.api.DeviceControlPort.DeviceAddress;
import vn.camhub.device.api.DeviceControlPort.Preset;
import vn.camhub.device.domain.Camera;
import vn.camhub.device.domain.StreamInfo;
import vn.camhub.device.infrastructure.CameraRepository;
import vn.camhub.platform.crypto.CryptoService;
import vn.camhub.platform.security.ApiException;

/** Hiện thực {@link DeviceApi}: ghép địa chỉ và tài khoản (giải mã trong bộ nhớ) rồi gọi Device Gateway. */
@Service
public class DeviceFacade implements DeviceApi {

    private final CameraRepository cameras;
    private final DeviceControlPort devices;
    private final CryptoService crypto;
    private final ObjectMapper json;
    private final CameraService paths;
    private final TunnelRouting routing;

    public DeviceFacade(CameraRepository cameras, DeviceControlPort devices, CryptoService crypto, ObjectMapper json,
            CameraService paths, TunnelRouting routing) {
        this.routing = routing;
        this.paths = paths;
        this.cameras = cameras;
        this.devices = devices;
        this.crypto = crypto;
        this.json = json;
    }

    @Override
    public Optional<CameraMedia> media(UUID cameraId) {
        return cameras.find(cameraId).map(c -> {
            List<StreamInfo> streams = cameras.streams(c.id());
            StreamInfo main = streams.stream().filter(s -> "MAIN".equals(s.type())).findFirst().orElse(null);
            StreamInfo sub = streams.stream().filter(s -> "SUB".equals(s.type())).findFirst().orElse(null);
            return new CameraMedia(c.id(), c.name(), c.siteId(), c.mediaPath(), sub != null,
                    main == null ? null : main.codec(), sub == null ? null : sub.codec(), needsTranscode(main),
                    needsTranscode(sub), capability(c, "ptz"), "ONVIF".equals(c.protocol()), c.status(),
                    cameras.recordingEnabled(c.id()), "ONVIF".equals(c.protocol()) && capability(c, "edgeRecording"));
        });
    }

    /** WebRTC trong trình duyệt chỉ chắc chắn phát được H.264 không B-frame (báo cáo PoC mục 4). */
    static boolean needsTranscode(StreamInfo s) {
        if (s == null) {
            return false;
        }
        String codec = s.codec() == null ? "" : s.codec().toUpperCase();
        return s.bFrames() || codec.contains("265") || codec.contains("HEVC");
    }

    @Override
    public Optional<PathOwner> cameraOfPath(String path) {
        return cameras.byMediaPath(path).map(o -> new PathOwner(o.cameraId(), o.organizationId()));
    }

    @Override
    public Optional<UUID> organizationOf(UUID cameraId) {
        return cameras.organizationOf(cameraId);
    }

    @Override
    public void ptzMove(UUID cameraId, double pan, double tilt, double zoom) {
        Target t = ptzTarget(cameraId);
        devices.ptzMove(t.address(), t.profile(), clamp(pan), clamp(tilt), clamp(zoom), 1000);
    }

    @Override
    public void ptzStop(UUID cameraId) {
        Target t = ptzTarget(cameraId);
        devices.ptzStop(t.address(), t.profile());
    }

    @Override
    public List<Preset> presets(UUID cameraId) {
        Target t = ptzTarget(cameraId);
        return devices.presets(t.address(), t.profile());
    }

    @Override
    public void gotoPreset(UUID cameraId, String presetToken) {
        Target t = ptzTarget(cameraId);
        devices.gotoPreset(t.address(), t.profile(), presetToken);
    }

    @Override
    public String setPreset(UUID cameraId, String name) {
        Target t = ptzTarget(cameraId);
        return devices.setPreset(t.address(), t.profile(), name);
    }

    @Override
    public void removePreset(UUID cameraId, String presetToken) {
        Target t = ptzTarget(cameraId);
        devices.removePreset(t.address(), t.profile(), presetToken);
    }

    @Override
    public byte[] snapshot(UUID cameraId) {
        Target t = onvifTarget(cameraId);
        return devices.snapshot(t.address(), t.profile());
    }

    @Override
    public DeviceControlPort.TalkSession openTalk(UUID cameraId) {
        Camera c = cameras.find(cameraId).orElseThrow(() -> ApiException.notFound("camera"));
        if (!"ONVIF".equals(c.protocol()) || !capability(c, "audioOut")) {
            throw new ApiException(409, "TALK_NOT_SUPPORTED", "Camera không có loa hoặc không hỗ trợ âm thanh hai chiều");
        }
        Target t = onvifTarget(cameraId);
        try {
            return devices.openTalk(t.address());
        } catch (DeviceControlPort.DeviceException e) {
            throw switch (e.code()) {
                case "BUSY" -> new ApiException(409, "TALK_BUSY", e.getMessage());
                case "NOT_SUPPORTED" -> new ApiException(409, "TALK_NOT_SUPPORTED", e.getMessage());
                default -> new ApiException(502, "DEVICE_ERROR", e.getMessage());
            };
        }
    }

    @Override
    public Optional<DeviceControlPort.TalkStatus> talkStatus(String sessionId) {
        return devices.talkStatus(sessionId);
    }

    @Override
    public Optional<DeviceControlPort.TalkStatus> closeTalk(String sessionId) {
        return devices.closeTalk(sessionId);
    }

    @Override
    public void setRecording(UUID cameraId, boolean on) {
        Camera c = cameras.find(cameraId).orElseThrow(() -> ApiException.notFound("camera"));
        if (cameras.recordingEnabled(cameraId) == on) {
            return;
        }
        cameras.setRecordingEnabled(cameraId, on);
        paths.syncPaths(c);
    }

    @Override
    public List<CameraState> states() {
        return cameras.all().stream()
                .map(c -> new CameraState(c.id(), c.name(), c.siteId(), c.zoneId(), c.status(), c.statusChangedAt(),
                        capability(c, "motionDetection"))).toList();
    }

    @Override
    public Optional<CameraState> state(UUID cameraId) {
        return cameras.find(cameraId).map(c -> new CameraState(c.id(), c.name(), c.siteId(), c.zoneId(), c.status(),
                c.statusChangedAt(), capability(c, "motionDetection")));
    }

    @Override
    public List<UUID> cameraIds() {
        return cameras.all().stream().map(Camera::id).toList();
    }

    @Override
    public List<DeviceControlPort.EncoderConfig> encoders(UUID cameraId) {
        return devices.encoders(onvifTarget(cameraId).address());
    }

    @Override
    public void setEncoder(UUID cameraId, DeviceControlPort.EncoderChange change) {
        devices.setEncoder(onvifTarget(cameraId).address(), change);
    }

    @Override
    public DeviceControlPort.RecordingSearch edgeRecordings(UUID cameraId, java.time.Instant from, java.time.Instant to) {
        Camera c = cameras.find(cameraId).orElseThrow(() -> ApiException.notFound("camera"));
        return devices.searchRecordings(onvifTarget(cameraId).address(), from, to, videoSource(c));
    }

    @Override
    public String openEdgeStream(UUID cameraId, String recordingToken, java.time.Instant start) {
        Camera c = cameras.find(cameraId).orElseThrow(() -> ApiException.notFound("camera"));
        Target t = onvifTarget(cameraId);
        String uri = devices.replayUri(t.address(), recordingToken, start);
        if (uri == null) {
            throw new ApiException(422, "NO_REPLAY_URI", "Thiết bị không trả URI phát lại");
        }
        return paths.openEdgePath(routing.rtsp(c.connectorId(), uri), c.username(), t.address().password());
    }

    /** Nguồn video của kênh đầu ghi (lưu trong capabilities khi thêm đầu ghi); camera độc lập: null. */
    private String videoSource(Camera c) {
        try {
            JsonNode n = json.readTree(c.capabilities() == null ? "{}" : c.capabilities());
            return n.hasNonNull("videoSource") ? n.get("videoSource").asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public DeviceControlPort.NetworkInfo network(UUID cameraId) {
        DeviceControlPort.NetworkInfo n = devices.network(onvifTarget(cameraId).address());
        cameras.recordClockDrift(cameraId, n.clockDriftSeconds());
        return n;
    }

    @Override
    public DeviceControlPort.NetworkSetResult setNetwork(UUID cameraId, DeviceControlPort.NetworkChange change) {
        Camera c = cameras.find(cameraId).orElseThrow(() -> ApiException.notFound("camera"));
        DeviceControlPort.NetworkSetResult r = devices.setNetwork(onvifTarget(cameraId).address(), change);
        String newHost = r.newHost() != null ? r.newHost() : change.dhcp() ? null : change.address();
        if (newHost != null && !newHost.equals(c.ipAddress())) {
            cameras.updateHost(cameraId, c.ipAddress(), newHost);
            paths.syncPaths(cameras.find(cameraId).orElseThrow());
        }
        return r;
    }

    @Override
    public void setTime(UUID cameraId, DeviceControlPort.TimeChange change) {
        devices.setTime(onvifTarget(cameraId).address(), change);
    }

    @Override
    public DeviceControlPort.ImagingSettings imaging(UUID cameraId) {
        return devices.imaging(onvifTarget(cameraId).address());
    }

    @Override
    public void setImaging(UUID cameraId, String videoSourceToken, DeviceControlPort.ImagingChange change) {
        devices.setImaging(onvifTarget(cameraId).address(), videoSourceToken, change);
    }

    @Override
    public DeviceControlPort.ZonesConfig zones(UUID cameraId) {
        return devices.zones(onvifTarget(cameraId).address());
    }

    @Override
    public void setMotionZones(UUID cameraId, DeviceControlPort.MotionZonesChange change) {
        devices.setMotionZones(onvifTarget(cameraId).address(), change);
    }

    @Override
    public void setPrivacyMasks(UUID cameraId, List<DeviceControlPort.PrivacyMask> masks) {
        devices.setPrivacyMasks(onvifTarget(cameraId).address(), masks);
    }

    record Target(DeviceAddress address, String profile) {
    }

    /** INV-05: lệnh PTZ chỉ hợp lệ khi camera báo hỗ trợ PTZ. */
    private Target ptzTarget(UUID cameraId) {
        Target t = onvifTarget(cameraId);
        Camera c = cameras.find(cameraId).orElseThrow();
        if (!capability(c, "ptz")) {
            throw new ApiException(409, "PTZ_NOT_SUPPORTED", "Camera không hỗ trợ PTZ");
        }
        return t;
    }

    private Target onvifTarget(UUID cameraId) {
        Camera c = cameras.find(cameraId).orElseThrow(() -> ApiException.notFound("camera"));
        if (!"ONVIF".equals(c.protocol())) {
            throw new ApiException(409, "ONVIF_REQUIRED", "Camera thêm bằng RTSP không điều khiển được qua CamHub");
        }
        String password = cameras.passwordEnc(cameraId).map(crypto::decrypt).orElse(null);
        String profile = cameras.streams(cameraId).stream().filter(s -> "MAIN".equals(s.type()))
                .map(StreamInfo::profileToken).findFirst().orElse(null);
        return new Target(new DeviceAddress(c.ipAddress(), c.port() == null ? 80 : c.port(), c.username(), password,
                routing.proxy(c.connectorId())), profile);
    }

    private boolean capability(Camera c, String name) {
        try {
            JsonNode n = json.readTree(c.capabilities() == null ? "{}" : c.capabilities());
            return n.path(name).asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    private static double clamp(double v) {
        return Math.max(-1, Math.min(1, v));
    }
}
