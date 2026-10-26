package vn.camhub.configuration.application;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import vn.camhub.access.api.AccessApi;
import vn.camhub.access.api.Permission;
import vn.camhub.device.api.DeviceApi;
import vn.camhub.device.api.DeviceControlPort.DeviceException;
import vn.camhub.device.api.DeviceControlPort.NetworkChange;
import vn.camhub.device.api.DeviceControlPort.NetworkInfo;
import vn.camhub.device.api.DeviceControlPort.NetworkSetResult;
import vn.camhub.device.api.DeviceControlPort.TimeChange;
import vn.camhub.platform.audit.AuditableEvent;
import vn.camhub.platform.security.ApiException;

/**
 * Cấu hình mạng và thời gian (US-25, UC-40): đổi IP tĩnh/DHCP (giao diện cảnh báo có thể mất kết nối tạm thời; thành công
 * thì địa chỉ đã lưu của camera được cập nhật – AC1), nguồn giờ NTP/thủ công; đọc độ lệch đồng hồ (AC2: > 5 giây).
 */
@Service
public class NetworkConfigService {

    public static final double DRIFT_LIMIT_SECONDS = 5.0;
    private static final Pattern IPV4 = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$");

    private final DeviceApi devices;
    private final AccessApi access;
    private final ApplicationEventPublisher events;

    public NetworkConfigService(DeviceApi devices, AccessApi access, ApplicationEventPublisher events) {
        this.devices = devices;
        this.access = access;
        this.events = events;
    }

    public NetworkInfo get(UUID actor, UUID cameraId) {
        require(actor, cameraId);
        return call(() -> devices.network(cameraId));
    }

    public NetworkSetResult setNetwork(UUID actor, UUID cameraId, NetworkChange c) {
        require(actor, cameraId);
        if (!c.dhcp() && (c.address() == null || !IPV4.matcher(c.address()).matches() || c.prefixLength() == null
                || c.prefixLength() < 1 || c.prefixLength() > 32
                || (c.gateway() != null && !c.gateway().isBlank() && !IPV4.matcher(c.gateway()).matches()))) {
            throw ApiException.badRequest("INVALID_NETWORK", "Địa chỉ IP, độ dài prefix hoặc gateway không hợp lệ");
        }
        NetworkChange clean = new NetworkChange(c.dhcp(), c.address(), c.prefixLength(),
                c.gateway() == null || c.gateway().isBlank() ? null : c.gateway(), c.dns());
        NetworkSetResult r = call(() -> devices.setNetwork(cameraId, clean));
        events.publishEvent(AuditableEvent.of(actor, "CHANGE_CONFIG", "CAMERA", cameraId.toString(), Map.of(
                "operation", "NETWORK_CONFIG", "dhcp", c.dhcp(), "address", c.address() == null ? "" : c.address(),
                "rebootNeeded", r.rebootNeeded())));
        return r;
    }

    public NetworkInfo setTime(UUID actor, UUID cameraId, TimeChange c) {
        require(actor, cameraId);
        if (c.mode() == null || !(c.mode().equals("NTP") || c.mode().equals("MANUAL"))) {
            throw ApiException.badRequest("INVALID_TIME", "Chọn nguồn giờ NTP hoặc thủ công");
        }
        call(() -> {
            devices.setTime(cameraId, c);
            return null;
        });
        events.publishEvent(AuditableEvent.of(actor, "CHANGE_CONFIG", "CAMERA", cameraId.toString(),
                Map.of("operation", "TIME_CONFIG", "mode", c.mode())));
        return call(() -> devices.network(cameraId));
    }

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
