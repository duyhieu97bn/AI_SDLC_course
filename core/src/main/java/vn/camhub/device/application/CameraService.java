package vn.camhub.device.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import vn.camhub.access.api.AccessApi;
import vn.camhub.access.api.Permission;
import vn.camhub.device.api.CameraChanged;
import vn.camhub.device.api.DeviceControlPort;
import vn.camhub.device.api.DeviceControlPort.DeviceAddress;
import vn.camhub.device.api.DeviceControlPort.DeviceException;
import vn.camhub.device.api.DeviceControlPort.DeviceInfo;
import vn.camhub.device.domain.Camera;
import vn.camhub.device.domain.RtspUrls;
import vn.camhub.device.domain.StreamInfo;
import vn.camhub.device.infrastructure.CameraRepository;
import vn.camhub.device.infrastructure.CameraRepository.NewCamera;
import vn.camhub.device.infrastructure.MediaMtxClient;
import vn.camhub.device.infrastructure.MediaMtxClient.PathConfig;
import vn.camhub.organization.api.OrganizationApi;
import vn.camhub.platform.audit.AuditableEvent;
import vn.camhub.platform.crypto.CryptoService;
import vn.camhub.platform.media.MediaTokenService;
import vn.camhub.platform.security.ApiException;
import vn.camhub.platform.tenancy.OrganizationContext;

/**
 * Thêm, kiểm tra kết nối, xóa camera (US-01, US-02, UC-10, UC-11).
 *
 * <ul>
 *   <li>ONVIF: Device Gateway đọc thông tin, khả năng, luồng chính/phụ (TS-05).</li>
 *   <li>RTSP: chỉ xem/ghi (INV-03); kiểm tra bằng path MediaMTX tạm thời, cũng là nguồn hình xem trước.</li>
 *   <li>Mật khẩu camera mã hóa AES-256-GCM (INV-17); URL lưu trong DB không có tài khoản.</li>
 * </ul>
 */
@Service
public class CameraService {

    public enum Protocol { ONVIF, RTSP }

    /** {@code siteId}: địa điểm của camera; địa điểm có bộ kết nối thì đi qua đường hầm (ADR-10). */
    public record ConnectionRequest(Protocol protocol, String host, Integer port, String username, String password,
            String streamUrl, UUID siteId) {

        ConnectionRequest withSite(UUID site) {
            return new ConnectionRequest(protocol, host, port, username, password, streamUrl, site);
        }
    }

    /** Kết quả kiểm tra kết nối; {@code preview} là URL WHEP/HLS kèm token (60 giây) tới path tạm. */
    public record ConnectionResult(boolean ok, String code, String message, String manufacturer, String model,
            String firmware, String macAddress, List<StreamInfo> streams, Map<String, Object> capabilities,
            Preview preview) {
    }

    public record Preview(String whepUrl, String hlsUrl, String token) {
    }

    public record CreateRequest(UUID zoneId, String name, ConnectionRequest connection, boolean saveUnverified) {
    }

    public record BulkDevice(String host, Integer port, String name) {
    }

    public record BulkResult(String host, boolean ok, UUID cameraId, String code, String message) {
    }

    private static final Logger log = LoggerFactory.getLogger(CameraService.class);
    static final Duration STREAM_WAIT = Duration.ofSeconds(10);
    static final Duration TEST_PATH_TTL = Duration.ofMinutes(2);

    private final CameraRepository cameras;
    private final DeviceControlPort devices;
    private final MediaMtxClient mediamtx;
    private final CryptoService crypto;
    private final AccessApi access;
    private final OrganizationApi organizations;
    private final MediaTokenService tokens;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final ObjectMapper json;
    private final TunnelRouting routing;
    private final Map<String, Instant> testPaths = new ConcurrentHashMap<>();
    private final Map<String, Instant> edgePaths = new ConcurrentHashMap<>();
    static final Duration EDGE_PATH_TTL = Duration.ofMinutes(30);

    public CameraService(CameraRepository cameras, DeviceControlPort devices, MediaMtxClient mediamtx,
            CryptoService crypto, AccessApi access, OrganizationApi organizations, MediaTokenService tokens,
            TransactionTemplate tx, ApplicationEventPublisher events, ObjectMapper json, TunnelRouting routing) {
        this.routing = routing;
        this.cameras = cameras;
        this.devices = devices;
        this.mediamtx = mediamtx;
        this.crypto = crypto;
        this.access = access;
        this.organizations = organizations;
        this.tokens = tokens;
        this.tx = tx;
        this.events = events;
        this.json = json;
    }

    // ---------------------------------------------------------------- kiểm tra kết nối (US-02 AC1, AC2)

    public ConnectionResult test(UUID actor, ConnectionRequest req) {
        if (!access.hasAnywhere(actor, Permission.CONFIGURE_CAMERA)) {
            throw ApiException.forbidden();
        }
        return probe(actor, req, true);
    }

    private ConnectionResult probe(UUID actor, ConnectionRequest req, boolean withPreview) {
        UUID connector = routing.connectorForSite(req.siteId()).orElse(null);
        try {
            if (req.protocol() == Protocol.ONVIF) {
                DeviceInfo info = devices.describe(new DeviceAddress(req.host(), port(req), req.username(),
                        req.password(), routing.proxy(connector)));
                List<StreamInfo> streams = toStreams(info);
                if (streams.isEmpty()) {
                    return failure("NO_STREAM", "Camera không có luồng video nào");
                }
                Preview preview = withPreview ? preview(actor, previewUrl(streams, req.username(), req.password(),
                        connector)) : null;
                return new ConnectionResult(true, null, null, info.manufacturer(), info.model(), info.firmware(),
                        info.macAddress(), streams, capabilities(info), preview);
            }
            RtspUrls.Parsed url = RtspUrls.parse(req.streamUrl());
            String user = url.username() != null ? url.username() : req.username();
            String pass = url.username() != null ? url.password() : req.password();
            String path = newTestPath();
            mediamtx.upsertPath(path, new PathConfig(RtspUrls.withCredentials(
                    routing.rtsp(connector, url.urlWithoutCredentials()), user, pass), false, false));
            var state = mediamtx.awaitReady(path, STREAM_WAIT);
            if (state.isEmpty()) {
                mediamtx.deletePath(path);
                testPaths.remove(path);
                return failure("STREAM_UNREACHABLE",
                        "Không nhận được luồng trong 10 giây: kiểm tra URL, tài khoản và mạng tới camera");
            }
            String codec = state.get().tracks().stream().findFirst().orElse(null);
            StreamInfo main = new StreamInfo("MAIN", null, codec, null, null, null, null, url.urlWithoutCredentials(),
                    false);
            Preview preview = withPreview ? previewFor(actor, path) : null;
            if (!withPreview) {
                mediamtx.deletePath(path);
                testPaths.remove(path);
            }
            return new ConnectionResult(true, null, null, null, null, null, null, List.of(main), Map.of(), preview);
        } catch (DeviceException e) {
            return failure(e.code(), deviceMessage(e));
        } catch (DeviceControlPort.GatewayUnavailableException
                 | io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
            return failure("GATEWAY_UNAVAILABLE", "Không kết nối được Device Gateway");
        } catch (IllegalArgumentException e) {
            return failure("INVALID_URL", e.getMessage());
        }
    }

    // ---------------------------------------------------------------- thêm camera (US-02)

    public Camera create(UUID actor, CreateRequest req) {
        UUID siteId = organizations.siteOfZone(req.zoneId()).orElseThrow(() -> ApiException.notFound("khu vực"));
        if (!access.canOnSite(actor, Permission.CONFIGURE_CAMERA, siteId)) {
            throw ApiException.forbidden();
        }
        ConnectionRequest c = req.connection() == null ? null : req.connection().withSite(siteId);
        if (c == null || c.protocol() == null) {
            throw ApiException.badRequest("PROTOCOL_REQUIRED", "Chọn kiểu kết nối ONVIF hoặc RTSP");
        }
        if (c.protocol() == Protocol.ONVIF && blank(c.host())) {
            throw ApiException.badRequest("HOST_REQUIRED", "Nhập địa chỉ IP camera");
        }
        if (c.protocol() == Protocol.RTSP && blank(c.streamUrl())) {
            throw ApiException.badRequest("STREAM_URL_REQUIRED", "Nhập URL RTSP");
        }
        ConnectionResult probe = probe(actor, c, false);
        if (!probe.ok() && !req.saveUnverified()) {
            // AC2: chỉ lưu được khi tick "Lưu dù chưa kết nối"
            throw new ApiException(422, probe.code(), probe.message(), Map.of("unverified", true));
        }
        UUID id = UUID.randomUUID();
        String host;
        Integer port;
        String streamUrl;
        String user;
        String pass;
        if (c.protocol() == Protocol.RTSP) {
            RtspUrls.Parsed u = RtspUrls.parse(c.streamUrl());
            host = u.host();
            port = u.port();
            streamUrl = u.urlWithoutCredentials();
            user = u.username() != null ? u.username() : c.username();
            pass = u.username() != null ? u.password() : c.password();
        } else {
            host = c.host().trim();
            port = port(c);
            streamUrl = null;
            user = c.username();
            pass = c.password();
        }
        String name = blank(req.name()) ? defaultName(probe, host) : req.name().trim();
        NewCamera row = new NewCamera(id, siteId, req.zoneId(), name, c.protocol().name(), host, port,
                c.protocol() == Protocol.RTSP ? streamUrl : null, blank(user) ? null : user,
                blank(pass) ? null : crypto.encrypt(pass), probe.manufacturer(), probe.model(),
                probe.macAddress(), probe.firmware(), null, toJson(probe.capabilities()), probe.ok(), null, null,
                routing.connectorForSite(siteId).orElse(null));
        tx.executeWithoutResult(s -> {
            cameras.insert(row);
            if (probe.streams() != null) {
                probe.streams().forEach(st -> cameras.insertStream(id, st));
            }
            organizations.recordInitialLocation(id, siteId, req.zoneId(), actor);
            events.publishEvent(AuditableEvent.of(actor, "CHANGE_CONFIG", "CAMERA", id.toString(), Map.of(
                    "operation", "ADD_CAMERA", "targetName", name, "protocol", c.protocol().name(),
                    "verified", probe.ok())));
        });
        Camera cam = cameras.find(id).orElseThrow();
        syncPaths(cam);
        events.publishEvent(new CameraChanged(OrganizationContext.require(), id, CameraChanged.Kind.ADDED));
        return cam;
    }

    /** Thêm hàng loạt từ kết quả dò tìm (US-01 AC3): từng camera thành công hay lỗi riêng, không dừng giữa chừng. */
    public List<BulkResult> createMany(UUID actor, UUID zoneId, String username, String password,
            List<BulkDevice> devicesToAdd) {
        List<BulkResult> results = new ArrayList<>();
        for (BulkDevice d : devicesToAdd) {
            try {
                Camera c = create(actor, new CreateRequest(zoneId, d.name(), new ConnectionRequest(Protocol.ONVIF,
                        d.host(), d.port(), username, password, null, null), false));
                results.add(new BulkResult(d.host(), true, c.id(), null, null));
            } catch (ApiException e) {
                results.add(new BulkResult(d.host(), false, null, e.code(), e.getMessage()));
            }
        }
        return results;
    }

    public void delete(UUID actor, UUID cameraId) {
        Camera c = cameras.find(cameraId).orElseThrow(() -> ApiException.notFound("camera"));
        if (!access.canOnCamera(actor, Permission.CONFIGURE_CAMERA, cameraId)) {
            throw ApiException.forbidden();
        }
        tx.executeWithoutResult(s -> {
            cameras.delete(cameraId);
            events.publishEvent(AuditableEvent.of(actor, "CHANGE_CONFIG", "CAMERA", cameraId.toString(),
                    Map.of("operation", "DELETE_CAMERA", "targetName", c.name())));
        });
        safely(() -> mediamtx.deletePath(c.mediaPath()));
        safely(() -> mediamtx.deletePath(c.subPath()));
        events.publishEvent(new CameraChanged(OrganizationContext.require(), cameraId, CameraChanged.Kind.REMOVED));
    }

    public void rename(UUID actor, UUID cameraId, String name) {
        Camera c = cameras.find(cameraId).orElseThrow(() -> ApiException.notFound("camera"));
        if (!access.canOnCamera(actor, Permission.CONFIGURE_CAMERA, cameraId)) {
            throw ApiException.forbidden();
        }
        tx.executeWithoutResult(s -> {
            cameras.rename(cameraId, name.trim());
            events.publishEvent(AuditableEvent.of(actor, "CHANGE_CONFIG", "CAMERA", cameraId.toString(),
                    Map.of("operation", "RENAME_CAMERA", "from", c.name(), "targetName", name.trim())));
        });
    }

    // ---------------------------------------------------------------- MediaMTX

    /** Cấu hình path luồng chính và luồng phụ của camera trên MediaMTX (gọi trong ngữ cảnh Tổ chức). */
    public void syncPaths(Camera c) {
        String user = c.username();
        String pass = cameras.passwordEnc(c.id()).map(crypto::decrypt).orElse(null);
        List<StreamInfo> streams = cameras.streams(c.id());
        String lanMain = streams.stream().filter(s -> "MAIN".equals(s.type())).map(StreamInfo::rtspUrl).findFirst()
                .orElse(c.streamUrl());
        if (lanMain == null) {
            return;
        }
        // Camera sau NAT: MediaMTX kéo qua cổng chuyển tiếp trên Tunnel Server
        String main = routing.rtsp(c.connectorId(), lanMain);
        // Ghi hình (US-16): MediaMTX kéo luồng liên tục và ghi đoạn 60 giây; không ghi thì chỉ kéo khi có người xem
        boolean record = cameras.recordingEnabled(c.id());
        mediamtx.upsertPath(c.mediaPath(), new PathConfig(RtspUrls.withCredentials(main, user, pass), !record, record));
        streams.stream().filter(s -> "SUB".equals(s.type())).findFirst().ifPresentOrElse(
                sub -> mediamtx.upsertPath(c.subPath(),
                        new PathConfig(RtspUrls.withCredentials(routing.rtsp(c.connectorId(), sub.rtspUrl()), user,
                                pass), true, false)),
                () -> safely(() -> mediamtx.deletePath(c.subPath())));
    }

    /**
     * Path tạm phát lại từ thiết bị (US-22): nguồn là URI replay của camera/đầu ghi (qua đường hầm nếu có), chỉ kéo
     * khi có người xem; tự xóa sau 30 phút.
     */
    public String openEdgePath(String replayUriLan, String user, String password) {
        String path = "edge-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        edgePaths.put(path, Instant.now());
        mediamtx.upsertPath(path, new PathConfig(RtspUrls.withCredentials(replayUriLan, user, password), true, false));
        return path;
    }

    @Scheduled(fixedDelay = 30_000)
    public void cleanTestPaths() {
        Instant edgeCutoff = Instant.now().minus(EDGE_PATH_TTL);
        edgePaths.entrySet().removeIf(e -> {
            if (e.getValue().isBefore(edgeCutoff)) {
                safely(() -> mediamtx.deletePath(e.getKey()));
                return true;
            }
            return false;
        });
        Instant cutoff = Instant.now().minus(TEST_PATH_TTL);
        testPaths.entrySet().removeIf(e -> {
            if (e.getValue().isBefore(cutoff)) {
                safely(() -> mediamtx.deletePath(e.getKey()));
                return true;
            }
            return false;
        });
    }

    private String newTestPath() {
        String p = "test-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        testPaths.put(p, Instant.now());
        return p;
    }

    private Preview preview(UUID actor, String rtspUrlWithCredentials) {
        if (rtspUrlWithCredentials == null) {
            return null;
        }
        String path = newTestPath();
        try {
            mediamtx.upsertPath(path, new PathConfig(rtspUrlWithCredentials, false, false));
            if (mediamtx.awaitReady(path, STREAM_WAIT).isEmpty()) {
                return null;
            }
            return previewFor(actor, path);
        } catch (RuntimeException e) {
            log.info("Không tạo được hình xem trước: {}", e.toString());
            return null;
        }
    }

    private Preview previewFor(UUID actor, String path) {
        String token = tokens.issue(path, MediaTokenService.Action.READ, actor, OrganizationContext.require());
        return new Preview("/media/webrtc/" + path + "/whep?token=" + token,
                "/media/hls/" + path + "/index.m3u8?token=" + token, token);
    }

    /** Xem trước dùng luồng phụ cho nhẹ; không có thì dùng luồng chính. */
    private String previewUrl(List<StreamInfo> streams, String user, String pass, UUID connector) {
        Optional<StreamInfo> s = streams.stream().filter(x -> "SUB".equals(x.type())).findFirst()
                .or(() -> streams.stream().filter(x -> "MAIN".equals(x.type())).findFirst());
        return s.map(x -> RtspUrls.withCredentials(routing.rtsp(connector, x.rtspUrl()), user, pass)).orElse(null);
    }

    static List<StreamInfo> toStreams(DeviceInfo info) {
        List<StreamInfo> out = new ArrayList<>();
        for (String type : List.of("MAIN", "SUB")) {
            info.profiles().stream().filter(p -> type.equals(p.type()) && p.rtspUrl() != null).findFirst()
                    .ifPresent(p -> out.add(new StreamInfo(type, p.token(), p.codec(), p.width(), p.height(), p.fps(),
                            p.bitrateKbps(), RtspUrls.parse(p.rtspUrl()).urlWithoutCredentials(), p.bFrames())));
        }
        return out;
    }

    static Map<String, Object> capabilities(DeviceInfo info) {
        var c = info.capabilities();
        if (c == null) {
            return Map.of();
        }
        return Map.of("ptz", c.ptz(), "audioIn", c.audioIn(), "audioOut", c.audioOut(), "imaging", c.imaging(),
                "events", c.events(), "edgeRecording", c.edgeRecording(), "motionDetection", c.motionDetection(),
                "privacyMask", c.privacyMask());
    }

    private String toJson(Map<String, Object> m) {
        try {
            return json.writeValueAsString(m == null ? Map.of() : m);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private static ConnectionResult failure(String code, String message) {
        return new ConnectionResult(false, code, message, null, null, null, null, List.of(), Map.of(), null);
    }

    static String deviceMessage(DeviceException e) {
        return switch (e.code()) {
            case "AUTH_FAILED" -> "Sai tên đăng nhập hoặc mật khẩu";
            case "UNREACHABLE" -> "Không tới được địa chỉ";
            case "NOT_ONVIF" -> "Thiết bị không hỗ trợ ONVIF hoặc sai cổng";
            case "GATEWAY_UNAVAILABLE" -> "Không kết nối được Device Gateway";
            default -> e.getMessage();
        };
    }

    private static String defaultName(ConnectionResult probe, String host) {
        String model = probe.model() == null ? "Camera" : probe.model();
        return model + " " + host;
    }

    private static int port(ConnectionRequest r) {
        return r.port() == null ? 80 : r.port();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static void safely(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            log.warn("Lỗi khi gọi MediaMTX: {}", e.toString());
        }
    }
}
