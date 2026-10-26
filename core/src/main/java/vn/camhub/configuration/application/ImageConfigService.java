package vn.camhub.configuration.application;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import vn.camhub.access.api.AccessApi;
import vn.camhub.access.api.Permission;
import vn.camhub.device.api.DeviceApi;
import vn.camhub.device.api.DeviceControlPort.DeviceException;
import vn.camhub.device.api.DeviceControlPort.ImagingChange;
import vn.camhub.device.api.DeviceControlPort.ImagingSettings;
import vn.camhub.device.api.DeviceControlPort.Range;
import vn.camhub.platform.audit.AuditableEvent;
import vn.camhub.platform.security.ApiException;

/**
 * Cấu hình hình ảnh (US-24, UC-40): độ sáng, tương phản, chế độ hồng ngoại, WDR theo khoảng camera hỗ trợ. Giao diện
 * áp từng thay đổi để xem trước trực tiếp và "Khôi phục giá trị trước" bằng cách áp lại giá trị lúc mở.
 */
@Service
public class ImageConfigService {

    public record ImageChange(String videoSourceToken, Double brightness, Double contrast, String irMode, Boolean wdr) {
    }

    private final DeviceApi devices;
    private final AccessApi access;
    private final ApplicationEventPublisher events;

    public ImageConfigService(DeviceApi devices, AccessApi access, ApplicationEventPublisher events) {
        this.devices = devices;
        this.access = access;
        this.events = events;
    }

    public ImagingSettings get(UUID actor, UUID cameraId) {
        require(actor, cameraId);
        return call(() -> devices.imaging(cameraId));
    }

    public ImagingSettings apply(UUID actor, UUID cameraId, ImageChange c) {
        require(actor, cameraId);
        ImagingSettings current = call(() -> devices.imaging(cameraId));
        var o = current.options();
        if (c.brightness() != null && !within(c.brightness(), o == null ? null : o.brightness())) {
            throw ApiException.badRequest("OUT_OF_RANGE", "Độ sáng ngoài khoảng camera hỗ trợ");
        }
        if (c.contrast() != null && !within(c.contrast(), o == null ? null : o.contrast())) {
            throw ApiException.badRequest("OUT_OF_RANGE", "Độ tương phản ngoài khoảng camera hỗ trợ");
        }
        if (c.irMode() != null && (!Set.of("AUTO", "DAY", "NIGHT").contains(c.irMode())
                || o == null || o.irModes() == null || !o.irModes().contains(c.irMode()))) {
            throw ApiException.badRequest("NOT_SUPPORTED", "Camera không hỗ trợ chế độ hồng ngoại này");
        }
        if (Boolean.TRUE.equals(c.wdr()) && (o == null || !o.wdr())) {
            throw ApiException.badRequest("NOT_SUPPORTED", "Camera không hỗ trợ WDR");
        }
        call(() -> {
            devices.setImaging(cameraId, c.videoSourceToken() == null ? current.videoSourceToken()
                    : c.videoSourceToken(), new ImagingChange(c.brightness(), c.contrast(), c.irMode(), c.wdr()));
            return null;
        });
        Map<String, Object> details = new java.util.HashMap<>(Map.of("operation", "IMAGE_CONFIG"));
        if (c.brightness() != null) {
            details.put("brightness", c.brightness());
        }
        if (c.contrast() != null) {
            details.put("contrast", c.contrast());
        }
        if (c.irMode() != null) {
            details.put("irMode", c.irMode());
        }
        if (c.wdr() != null) {
            details.put("wdr", c.wdr());
        }
        events.publishEvent(AuditableEvent.of(actor, "CHANGE_CONFIG", "CAMERA", cameraId.toString(), details));
        return call(() -> devices.imaging(cameraId));
    }

    private static boolean within(double v, Range r) {
        return r == null || (v >= r.min() && v <= r.max());
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
