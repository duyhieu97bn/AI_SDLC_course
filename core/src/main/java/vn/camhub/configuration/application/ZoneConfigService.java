package vn.camhub.configuration.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import vn.camhub.access.api.AccessApi;
import vn.camhub.access.api.Permission;
import vn.camhub.configuration.domain.MotionZone;
import vn.camhub.configuration.domain.MotionZone.Point;
import vn.camhub.configuration.domain.MotionZone.Violation;
import vn.camhub.device.api.DeviceApi;
import vn.camhub.device.api.DeviceControlPort;
import vn.camhub.device.api.DeviceControlPort.DeviceException;
import vn.camhub.device.api.DeviceControlPort.MotionZones;
import vn.camhub.device.api.DeviceControlPort.PrivacyMasks;
import vn.camhub.device.api.DeviceControlPort.ZonesConfig;
import vn.camhub.platform.audit.AuditableEvent;
import vn.camhub.platform.security.ApiException;

/** Vùng chuyển động (lưới ô hoặc đa giác, độ nhạy) và vùng che riêng tư (US-26, UC-41). Cần CONFIGURE_CAMERA. */
@Service
public class ZoneConfigService {

    public record MotionChange(String mode, List<List<Boolean>> cells, List<List<Point>> polygons,
            Integer sensitivity) {
    }

    public record MaskChange(List<Point> points, Boolean enabled) {
    }

    private final DeviceApi devices;
    private final AccessApi access;
    private final ApplicationEventPublisher events;

    public ZoneConfigService(DeviceApi devices, AccessApi access, ApplicationEventPublisher events) {
        this.devices = devices;
        this.access = access;
        this.events = events;
    }

    public ZonesConfig get(UUID actor, UUID cameraId) {
        require(actor, cameraId);
        return call(() -> devices.zones(cameraId));
    }

    public ZonesConfig setMotion(UUID actor, UUID cameraId, MotionChange c) {
        require(actor, cameraId);
        MotionZones current = call(() -> devices.zones(cameraId)).motion();
        if (current == null || !current.supported()) {
            throw ApiException.badRequest("NOT_SUPPORTED", "Camera không hỗ trợ cấu hình vùng chuyển động");
        }
        if (!MotionZone.validSensitivity(c.sensitivity())) {
            throw ApiException.badRequest("INVALID_ZONE", "Độ nhạy trong khoảng 0–100");
        }
        List<Violation> v = switch (c.mode() == null ? "" : c.mode()) {
            case "CELL" -> current.grid() == null
                    ? List.of(new Violation("NOT_SUPPORTED", "Camera không hỗ trợ lưới ô"))
                    : MotionZone.validateCells(c.cells(), current.grid().columns(), current.grid().rows());
            case "POLYGON" -> MotionZone.validatePolygons(c.polygons(), current.maxPolygons());
            default -> List.of(new Violation("INVALID_ZONE", "Chế độ vùng là CELL hoặc POLYGON"));
        };
        reject(v);
        call(() -> {
            devices.setMotionZones(cameraId, new DeviceControlPort.MotionZonesChange(c.mode(),
                    "CELL".equals(c.mode()) ? c.cells() : null,
                    "POLYGON".equals(c.mode()) ? toDevice(c.polygons()) : null, c.sensitivity()));
            return null;
        });
        events.publishEvent(AuditableEvent.of(actor, "CHANGE_CONFIG", "CAMERA", cameraId.toString(), Map.of(
                "operation", "MOTION_ZONES", "mode", c.mode(),
                "zones", "POLYGON".equals(c.mode()) ? c.polygons().size() : 1,
                "sensitivity", c.sensitivity() == null ? "" : c.sensitivity())));
        return call(() -> devices.zones(cameraId));
    }

    public ZonesConfig setMasks(UUID actor, UUID cameraId, List<MaskChange> masks) {
        require(actor, cameraId);
        PrivacyMasks current = call(() -> devices.zones(cameraId)).masks();
        if (current == null || !current.supported()) {
            throw ApiException.badRequest("NOT_SUPPORTED", "Camera không hỗ trợ vùng che riêng tư");
        }
        reject(MotionZone.validateMasks(masks.stream().map(MaskChange::points).toList(), current.maxMasks(),
                current.maxPoints()));
        call(() -> {
            devices.setPrivacyMasks(cameraId, masks.stream().map(m -> new DeviceControlPort.PrivacyMask(null,
                    m.points().stream().map(p -> new DeviceControlPort.Point(p.x(), p.y())).toList(),
                    m.enabled() == null || m.enabled())).toList());
            return null;
        });
        events.publishEvent(AuditableEvent.of(actor, "CHANGE_CONFIG", "CAMERA", cameraId.toString(), Map.of(
                "operation", "PRIVACY_MASKS", "masks", masks.size())));
        return call(() -> devices.zones(cameraId));
    }

    private static List<List<DeviceControlPort.Point>> toDevice(List<List<Point>> polygons) {
        return polygons.stream().map(p -> p.stream().map(x -> new DeviceControlPort.Point(x.x(), x.y())).toList())
                .toList();
    }

    private static void reject(List<Violation> v) {
        if (!v.isEmpty()) {
            throw ApiException.badRequest(v.get(0).code(), v.get(0).message());
        }
    }

    /** Lỗi thiết bị (LIMIT_EXCEEDED, NOT_SUPPORTED, AUTH_FAILED...) trả về giao diện với mã ổn định. */
    private static <T> T call(java.util.function.Supplier<T> work) {
        try {
            return work.get();
        } catch (DeviceException e) {
            throw new ApiException(422, e.code(), e.getMessage());
        }
    }

    private void require(UUID actor, UUID cameraId) {
        devices.media(cameraId).orElseThrow(() -> ApiException.notFound("camera"));
        if (!access.canOnCamera(actor, Permission.CONFIGURE_CAMERA, cameraId)) {
            throw ApiException.forbidden();
        }
    }
}
