package vn.camhub.configuration.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import vn.camhub.access.api.AccessApi;
import vn.camhub.access.api.Permission;
import vn.camhub.configuration.application.CapabilityValidator.ParameterError;
import vn.camhub.device.api.DeviceApi;
import vn.camhub.device.api.DeviceControlPort.DeviceException;
import vn.camhub.device.api.DeviceControlPort.EncoderChange;
import vn.camhub.device.api.DeviceControlPort.EncoderConfig;
import vn.camhub.platform.audit.AuditableEvent;
import vn.camhub.platform.security.ApiException;

/**
 * Cấu hình luồng video (US-23, UC-40): đọc cấu hình encoder và khả năng của camera, áp thay đổi và báo kết quả từng
 * tham số. Camera từ chối cả lệnh thì thử lại từng tham số để chỉ ra đúng tham số lỗi (thành công một phần).
 */
@Service
public class StreamConfigService {

    public record ParameterResult(String configurationToken, String parameter, boolean ok, String message) {
    }

    public record ApplyResult(String result, List<ParameterResult> parameters) {
    }

    private final DeviceApi devices;
    private final AccessApi access;
    private final ApplicationEventPublisher events;

    public StreamConfigService(DeviceApi devices, AccessApi access, ApplicationEventPublisher events) {
        this.devices = devices;
        this.access = access;
        this.events = events;
    }

    public List<EncoderConfig> get(UUID actor, UUID cameraId) {
        require(actor, cameraId);
        return devices.encoders(cameraId);
    }

    public ApplyResult apply(UUID actor, UUID cameraId, List<EncoderChange> changes) {
        require(actor, cameraId);
        List<EncoderConfig> current = devices.encoders(cameraId);
        List<ParameterResult> results = new ArrayList<>();
        for (EncoderChange c : changes) {
            EncoderConfig cfg = current.stream().filter(x -> x.token().equals(c.configurationToken())).findFirst()
                    .orElseThrow(() -> ApiException.badRequest("UNKNOWN_CONFIGURATION",
                            "Không có cấu hình encoder " + c.configurationToken()));
            List<ParameterError> invalid = CapabilityValidator.validate(c, cfg.options());
            if (!invalid.isEmpty()) {
                invalid.forEach(e -> results.add(new ParameterResult(c.configurationToken(), e.parameter(), false,
                        e.message())));
                continue;
            }
            try {
                devices.setEncoder(cameraId, c);
                params(c).forEach(p -> results.add(new ParameterResult(c.configurationToken(), p, true, null)));
            } catch (DeviceException whole) {
                // Thử từng tham số để biết tham số nào camera không nhận
                for (String p : params(c)) {
                    try {
                        devices.setEncoder(cameraId, only(c, p));
                        results.add(new ParameterResult(c.configurationToken(), p, true, null));
                    } catch (DeviceException e) {
                        results.add(new ParameterResult(c.configurationToken(), p, false, e.getMessage()));
                    }
                }
            }
        }
        long failed = results.stream().filter(r -> !r.ok()).count();
        String overall = failed == 0 ? "SUCCESS" : failed == results.size() ? "FAILED" : "PARTIAL";
        events.publishEvent(AuditableEvent.of(actor, "CHANGE_CONFIG", "CAMERA", cameraId.toString(), Map.of(
                "operation", "STREAM_CONFIG", "result", overall, "parameters", results.size(), "failed", failed)));
        return new ApplyResult(overall, results);
    }

    static List<String> params(EncoderChange c) {
        List<String> p = new ArrayList<>();
        if (c.codec() != null) {
            p.add("codec");
        }
        if (c.width() != null) {
            p.add("resolution");
        }
        if (c.fps() != null) {
            p.add("fps");
        }
        if (c.bitrateKbps() != null) {
            p.add("bitrateKbps");
        }
        if (c.govLength() != null) {
            p.add("govLength");
        }
        return p;
    }

    static EncoderChange only(EncoderChange c, String param) {
        String t = c.configurationToken();
        return switch (param) {
            case "codec" -> new EncoderChange(t, c.codec(), null, null, null, null, null);
            case "resolution" -> new EncoderChange(t, null, c.width(), c.height(), null, null, null);
            case "fps" -> new EncoderChange(t, null, null, null, c.fps(), null, null);
            case "bitrateKbps" -> new EncoderChange(t, null, null, null, null, c.bitrateKbps(), null);
            default -> new EncoderChange(t, null, null, null, null, null, c.govLength());
        };
    }

    private void require(UUID actor, UUID cameraId) {
        devices.media(cameraId).orElseThrow(() -> ApiException.notFound("camera"));
        if (!access.canOnCamera(actor, Permission.CONFIGURE_CAMERA, cameraId)) {
            throw ApiException.forbidden();
        }
    }
}
