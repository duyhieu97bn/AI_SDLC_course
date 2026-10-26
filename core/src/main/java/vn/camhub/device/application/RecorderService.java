package vn.camhub.device.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import vn.camhub.access.api.AccessApi;
import vn.camhub.access.api.Permission;
import vn.camhub.device.api.CameraChanged;
import vn.camhub.device.api.DeviceControlPort;
import vn.camhub.device.api.DeviceControlPort.DeviceAddress;
import vn.camhub.device.api.DeviceControlPort.DeviceException;
import vn.camhub.device.api.DeviceControlPort.RecorderChannel;
import vn.camhub.device.api.DeviceControlPort.RecorderInfo;
import vn.camhub.device.domain.Camera;
import vn.camhub.device.domain.RtspUrls;
import vn.camhub.device.domain.StreamInfo;
import vn.camhub.device.infrastructure.CameraRepository;
import vn.camhub.device.infrastructure.CameraRepository.NewCamera;
import vn.camhub.device.infrastructure.RecorderRepository;
import vn.camhub.device.infrastructure.RecorderRepository.Recorder;
import vn.camhub.organization.api.OrganizationApi;
import vn.camhub.platform.audit.AuditableEvent;
import vn.camhub.platform.crypto.CryptoService;
import vn.camhub.platform.security.ApiException;
import vn.camhub.platform.tenancy.OrganizationContext;

/**
 * Đầu ghi NVR/DVR (US-03, UC-12): đọc danh sách kênh, tạo một camera cho mỗi kênh được chọn (tên lấy từ đầu ghi).
 * Trạng thái kết nối theo đầu ghi: đầu ghi mất kết nối thì mọi kênh mất kết nối (AC2).
 */
@Service
public class RecorderService {

    public record Connection(String host, Integer port, String username, String password) {
    }

    public record ChannelChoice(int channel, String name) {
    }

    public record Created(UUID recorderId, List<UUID> cameraIds) {
    }

    private final RecorderRepository recorders;
    private final CameraRepository cameras;
    private final DeviceControlPort devices;
    private final CameraService cameraService;
    private final TunnelRouting routing;
    private final CryptoService crypto;
    private final AccessApi access;
    private final OrganizationApi organizations;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;

    public RecorderService(RecorderRepository recorders, CameraRepository cameras, DeviceControlPort devices,
            CameraService cameraService, TunnelRouting routing, CryptoService crypto, AccessApi access,
            OrganizationApi organizations, TransactionTemplate tx, ApplicationEventPublisher events) {
        this.recorders = recorders;
        this.cameras = cameras;
        this.devices = devices;
        this.cameraService = cameraService;
        this.routing = routing;
        this.crypto = crypto;
        this.access = access;
        this.organizations = organizations;
        this.tx = tx;
        this.events = events;
    }

    public List<Recorder> list(UUID actor) {
        if (!access.hasAnywhere(actor, Permission.CONFIGURE_CAMERA)) {
            throw ApiException.forbidden();
        }
        return recorders.all().stream().filter(r -> access.canOnSite(actor, Permission.CONFIGURE_CAMERA, r.siteId()))
                .toList();
    }

    /** Đọc kênh của đầu ghi để người dùng chọn (US-03 AC1). */
    public RecorderInfo probe(UUID actor, UUID siteId, Connection c) {
        if (!access.canOnSite(actor, Permission.CONFIGURE_CAMERA, siteId)) {
            throw ApiException.forbidden();
        }
        return channels(siteId, c);
    }

    public Created create(UUID actor, UUID zoneId, String name, Connection c, List<ChannelChoice> chosen) {
        UUID siteId = organizations.siteOfZone(zoneId).orElseThrow(() -> ApiException.notFound("khu vực"));
        if (!access.canOnSite(actor, Permission.CONFIGURE_CAMERA, siteId)) {
            throw ApiException.forbidden();
        }
        if (chosen == null || chosen.isEmpty()) {
            throw ApiException.badRequest("NO_CHANNEL", "Chọn ít nhất một kênh");
        }
        RecorderInfo info = channels(siteId, c);
        Map<Integer, RecorderChannel> byNo = info.channels().stream()
                .collect(Collectors.toMap(RecorderChannel::channel, ch -> ch));
        Set<Integer> picked = chosen.stream().map(ChannelChoice::channel).collect(Collectors.toSet());
        for (Integer n : picked) {
            RecorderChannel ch = byNo.get(n);
            if (ch == null || !ch.hasSignal() || ch.rtspUrl() == null) {
                throw ApiException.badRequest("CHANNEL_NO_SIGNAL", "Kênh " + n + " không có tín hiệu video");
            }
        }
        UUID connector = routing.connectorForSite(siteId).orElse(null);
        UUID recorderId = UUID.randomUUID();
        String recorderName = name == null || name.isBlank()
                ? (info.manufacturer() == null ? "" : info.manufacturer() + " ") + c.host() : name.trim();
        String passwordEnc = c.password() == null || c.password().isBlank() ? null : crypto.encrypt(c.password());
        int port = c.port() == null ? 80 : c.port();
        List<UUID> created = new ArrayList<>();
        tx.executeWithoutResult(s -> {
            recorders.insert(recorderId, siteId, zoneId, recorderName, c.host().trim(), port, c.username(), passwordEnc,
                    info.manufacturer(), info.model(), info.channelCount(), connector, actor);
            for (ChannelChoice choice : chosen) {
                RecorderChannel ch = byNo.get(choice.channel());
                UUID id = UUID.randomUUID();
                // AC1: tên kênh lấy từ đầu ghi (người dùng có thể sửa trước khi lưu)
                String camName = choice.name() == null || choice.name().isBlank() ? ch.name() : choice.name().trim();
                cameras.insert(new NewCamera(id, siteId, zoneId, camName, "ONVIF", c.host().trim(), port, null,
                        c.username(), passwordEnc, info.manufacturer(), info.model(), null, null, null,
                        capabilitiesOf(ch, info), true, recorderId, ch.channel(), connector));
                cameras.insertStream(id, stream("MAIN", ch.profileToken(), ch.rtspUrl(), ch));
                if (ch.subRtspUrl() != null) {
                    cameras.insertStream(id, stream("SUB", ch.subProfileToken(), ch.subRtspUrl(), null));
                }
                organizations.recordInitialLocation(id, siteId, zoneId, actor);
                created.add(id);
            }
            events.publishEvent(AuditableEvent.of(actor, "CHANGE_CONFIG", "RECORDER", recorderId.toString(), Map.of(
                    "operation", "ADD_RECORDER", "targetName", recorderName, "channels", chosen.size())));
        });
        for (UUID id : created) {
            Camera cam = cameras.find(id).orElseThrow();
            try {
                cameraService.syncPaths(cam);
            } catch (RuntimeException e) {
                // MediaMTX tạm lỗi: DeviceSync bổ sung path ở lần đồng bộ sau
            }
            events.publishEvent(new CameraChanged(OrganizationContext.require(), id, CameraChanged.Kind.ADDED));
        }
        return new Created(recorderId, created);
    }

    private RecorderInfo channels(UUID siteId, Connection c) {
        if (c == null || c.host() == null || c.host().isBlank()) {
            throw ApiException.badRequest("HOST_REQUIRED", "Nhập địa chỉ IP đầu ghi");
        }
        UUID connector = routing.connectorForSite(siteId).orElse(null);
        try {
            return devices.recorderChannels(new DeviceAddress(c.host().trim(), c.port() == null ? 80 : c.port(),
                    c.username(), c.password(), routing.proxy(connector)));
        } catch (DeviceException e) {
            throw new ApiException(422, e.code(), CameraService.deviceMessage(e));
        } catch (DeviceControlPort.GatewayUnavailableException
                 | io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
            throw new ApiException(503, "GATEWAY_UNAVAILABLE", "Không kết nối được Device Gateway");
        }
    }

    /** Kênh đầu ghi: không PTZ; xem lại từ thiết bị qua nguồn video của kênh (US-22). */
    private static String capabilitiesOf(RecorderChannel ch, RecorderInfo info) {
        return "{\"ptz\":false,\"edgeRecording\":true,\"videoSource\":\"" + ch.videoSourceToken().replace("\"", "")
                + "\"}";
    }

    private static StreamInfo stream(String type, String profile, String url, RecorderChannel ch) {
        Integer w = ch == null || ch.resolution() == null ? null : ch.resolution().width();
        Integer h = ch == null || ch.resolution() == null ? null : ch.resolution().height();
        return new StreamInfo(type, profile, null, w, h, null, null, RtspUrls.parse(url).urlWithoutCredentials(),
                false);
    }
}
