package vn.camhub.device.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import vn.camhub.access.api.AccessApi;
import vn.camhub.access.api.Permission;
import vn.camhub.device.api.CameraChanged;
import vn.camhub.device.api.DeviceControlPort;
import vn.camhub.device.api.DeviceControlPort.DeviceAddress;
import vn.camhub.device.api.DeviceControlPort.DeviceException;
import vn.camhub.device.domain.Camera;
import vn.camhub.device.infrastructure.CameraRepository;
import vn.camhub.platform.audit.AuditableEvent;
import vn.camhub.platform.crypto.CryptoService;
import vn.camhub.platform.realtime.RealtimePublisher;
import vn.camhub.platform.security.ApiException;

/**
 * Bảo trì camera (US-28, UC-43): đổi mật khẩu hàng loạt – camera đổi thành công thì cập nhật mật khẩu đã lưu, thất bại
 * giữ mật khẩu cũ và báo lỗi từng camera (AC1); khởi động lại và theo dõi đến khi camera trực tuyến lại (AC2).
 */
@Service
public class MaintenanceService {

    static final Duration REBOOT_TIMEOUT = Duration.ofMinutes(10);

    public record PasswordResult(UUID cameraId, String name, boolean ok, String code, String message) {
    }

    /** Trạng thái khởi động lại gửi về người yêu cầu qua {@code /user/queue/reboot}. */
    public record RebootStatus(UUID cameraId, String name, String state, Instant requestedAt, Instant at) {
    }

    private record Pending(UUID actor, UUID org, String name, Instant requestedAt) {
    }

    private final CameraRepository cameras;
    private final DeviceControlPort devices;
    private final CameraService cameraService;
    private final TunnelRouting routing;
    private final CryptoService crypto;
    private final AccessApi access;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final RealtimePublisher realtime;
    private final Clock clock;
    private final Map<UUID, Pending> rebooting = new ConcurrentHashMap<>();

    public MaintenanceService(CameraRepository cameras, DeviceControlPort devices, CameraService cameraService,
            TunnelRouting routing, CryptoService crypto, AccessApi access, TransactionTemplate tx,
            ApplicationEventPublisher events, RealtimePublisher realtime, Clock clock) {
        this.cameras = cameras;
        this.devices = devices;
        this.cameraService = cameraService;
        this.routing = routing;
        this.crypto = crypto;
        this.access = access;
        this.tx = tx;
        this.events = events;
        this.realtime = realtime;
        this.clock = clock;
    }

    public List<PasswordResult> changePasswords(UUID actor, List<UUID> cameraIds, String newPassword) {
        if (newPassword == null || newPassword.length() < 8) {
            throw ApiException.badRequest("WEAK_DEVICE_PASSWORD", "Mật khẩu camera tối thiểu 8 ký tự");
        }
        List<PasswordResult> out = new ArrayList<>();
        for (UUID id : cameraIds) {
            out.add(changeOne(actor, id, newPassword));
        }
        return out;
    }

    private PasswordResult changeOne(UUID actor, UUID id, String newPassword) {
        Camera c = cameras.find(id).orElse(null);
        if (c == null) {
            return new PasswordResult(id, null, false, "NOT_FOUND", "Không tìm thấy camera");
        }
        if (!access.canOnCamera(actor, Permission.CONFIGURE_CAMERA, id)) {
            return new PasswordResult(id, c.name(), false, "FORBIDDEN", "Không có quyền cấu hình camera này");
        }
        if (!"ONVIF".equals(c.protocol()) || c.recorderId() != null || c.username() == null) {
            return new PasswordResult(id, c.name(), false, "ONVIF_REQUIRED",
                    "Chỉ đổi được mật khẩu camera ONVIF (kênh đầu ghi đổi trên đầu ghi)");
        }
        try {
            devices.changePassword(address(c), c.username(), newPassword);
        } catch (DeviceException e) {
            audit(actor, c, "CHANGE_CAMERA_PASSWORD", false, e.code());
            return new PasswordResult(id, c.name(), false, e.code(), CameraService.deviceMessage(e));
        } catch (DeviceControlPort.GatewayUnavailableException
                 | io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
            audit(actor, c, "CHANGE_CAMERA_PASSWORD", false, "GATEWAY_UNAVAILABLE");
            return new PasswordResult(id, c.name(), false, "GATEWAY_UNAVAILABLE", "Không kết nối được Device Gateway");
        }
        tx.executeWithoutResult(s -> {
            cameras.updatePassword(id, crypto.encrypt(newPassword));
            audit(actor, c, "CHANGE_CAMERA_PASSWORD", true, null);
        });
        // MediaMTX cần tài khoản mới để kéo luồng
        try {
            cameraService.syncPaths(cameras.find(id).orElseThrow());
        } catch (RuntimeException e) {
            // DeviceSync đồng bộ lại sau
        }
        return new PasswordResult(id, c.name(), true, null, null);
    }

    public RebootStatus reboot(UUID actor, UUID id) {
        Camera c = cameras.find(id).orElseThrow(() -> ApiException.notFound("camera"));
        if (!access.canOnCamera(actor, Permission.CONFIGURE_CAMERA, id)) {
            throw ApiException.forbidden();
        }
        if (!"ONVIF".equals(c.protocol()) || c.recorderId() != null) {
            throw new ApiException(409, "ONVIF_REQUIRED", "Chỉ khởi động lại được camera ONVIF");
        }
        try {
            devices.reboot(address(c));
        } catch (DeviceException e) {
            throw new ApiException(422, e.code(), CameraService.deviceMessage(e));
        }
        Instant now = clock.instant();
        rebooting.put(id, new Pending(actor, vn.camhub.platform.tenancy.OrganizationContext.require(), c.name(), now));
        tx.executeWithoutResult(s -> audit(actor, c, "REBOOT_CAMERA", true, null));
        return new RebootStatus(id, c.name(), "REBOOTING", now, now);
    }

    /** Camera trực tuyến lại sau khi khởi động lại: báo người yêu cầu (AC2). */
    @EventListener
    public void onCameraChanged(CameraChanged e) {
        if (e.kind() != CameraChanged.Kind.ONLINE) {
            return;
        }
        Pending p = rebooting.remove(e.cameraId());
        if (p != null) {
            realtime.toUser(p.actor(), "reboot", new RebootStatus(e.cameraId(), p.name(), "ONLINE", p.requestedAt(),
                    clock.instant()));
        }
    }

    public RebootStatus rebootStatus(UUID actor, UUID id) {
        Camera c = cameras.find(id).orElseThrow(() -> ApiException.notFound("camera"));
        if (!access.canOnCamera(actor, Permission.CONFIGURE_CAMERA, id)) {
            throw ApiException.forbidden();
        }
        Pending p = rebooting.get(id);
        if (p != null) {
            return new RebootStatus(id, c.name(), "REBOOTING", p.requestedAt(), clock.instant());
        }
        return new RebootStatus(id, c.name(), c.status(), null, c.statusChangedAt());
    }

    @Scheduled(fixedDelay = 60_000)
    public void expireReboots() {
        Instant cutoff = clock.instant().minus(REBOOT_TIMEOUT);
        rebooting.entrySet().removeIf(en -> {
            if (en.getValue().requestedAt().isBefore(cutoff)) {
                realtime.toUser(en.getValue().actor(), "reboot", new RebootStatus(en.getKey(), en.getValue().name(),
                        "TIMEOUT", en.getValue().requestedAt(), clock.instant()));
                return true;
            }
            return false;
        });
    }

    private DeviceAddress address(Camera c) {
        String password = cameras.passwordEnc(c.id()).map(crypto::decrypt).orElse(null);
        return new DeviceAddress(c.ipAddress(), c.port() == null ? 80 : c.port(), c.username(), password,
                routing.proxy(c.connectorId()));
    }

    private void audit(UUID actor, Camera c, String action, boolean ok, String code) {
        Map<String, Object> details = new java.util.HashMap<>(Map.of("targetName", c.name()));
        if (code != null) {
            details.put("code", code);
        }
        events.publishEvent(ok ? AuditableEvent.of(actor, action, "CAMERA", c.id().toString(), details)
                : AuditableEvent.failure(actor, action, "CAMERA", c.id().toString(), details));
    }
}
