package vn.camhub.configuration.application;

import java.util.ArrayList;
import java.util.List;
import vn.camhub.device.api.DeviceControlPort.EncoderChange;
import vn.camhub.device.api.DeviceControlPort.EncoderOptions;
import vn.camhub.device.api.DeviceControlPort.IntRange;

/**
 * US-29: chỉ chấp nhận giá trị camera báo hỗ trợ (codec, độ phân giải, fps, bitrate, GOP). Lỗi trả theo từng tham số
 * để giao diện hiển thị đúng chỗ, và không gửi lệnh vô ích xuống thiết bị.
 */
public final class CapabilityValidator {

    public record ParameterError(String parameter, String message) {
    }

    private CapabilityValidator() {
    }

    public static List<ParameterError> validate(EncoderChange c, EncoderOptions o) {
        List<ParameterError> errors = new ArrayList<>();
        if (o == null) {
            return errors;
        }
        if (c.codec() != null && o.codecs() != null && !o.codecs().isEmpty()
                && o.codecs().stream().noneMatch(x -> x.equalsIgnoreCase(c.codec()))) {
            errors.add(new ParameterError("codec", "Camera không hỗ trợ codec " + c.codec()));
        }
        if (c.width() != null && o.resolutions() != null && !o.resolutions().isEmpty()
                && o.resolutions().stream().noneMatch(r -> r.width() == c.width() && r.height() == c.height())) {
            errors.add(new ParameterError("resolution",
                    "Camera không hỗ trợ độ phân giải " + c.width() + "×" + c.height()));
        }
        range(errors, "fps", c.fps(), o.fpsRange());
        range(errors, "bitrateKbps", c.bitrateKbps(), o.bitrateRange());
        range(errors, "govLength", c.govLength(), o.govLengthRange());
        return errors;
    }

    private static void range(List<ParameterError> errors, String name, Integer v, IntRange r) {
        if (v != null && r != null && (v < r.min() || v > r.max())) {
            errors.add(new ParameterError(name, "Giá trị phải trong khoảng " + r.min() + "–" + r.max()));
        }
    }
}
