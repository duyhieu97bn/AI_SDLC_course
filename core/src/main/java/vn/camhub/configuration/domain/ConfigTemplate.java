package vn.camhub.configuration.domain;

import java.util.List;
import java.util.UUID;

/**
 * Cấu hình mẫu (US-27, class diagram mục 5): gồm các phần chính sách ghi, luồng chính, hình ảnh; phần null thì không
 * áp. Áp cho nhiều camera: tham số camera không hỗ trợ được bỏ qua (kết quả PARTIAL), lỗi thiết bị → FAILED.
 */
public record ConfigTemplate(UUID id, String name, String description, RecordingSpec recording, StreamSpec stream,
        ImageSpec image) {

    public record Slot(int day, String from, String to) {
    }

    public record RecordingSpec(String mode, List<Slot> schedule, int preEventSeconds, int postEventSeconds) {
    }

    public record StreamSpec(String codec, Integer width, Integer height, Integer fps, Integer bitrateKbps,
            Integer govLength) {
    }

    public record ImageSpec(Double brightness, Double contrast, String irMode, Boolean wdr) {
    }

    public enum Result { SUCCESS, PARTIAL, FAILED }

    /** Một dòng kết quả: phần (RECORDING/STREAM/IMAGE), tham số, trạng thái OK / SKIPPED / ERROR. */
    public record Detail(String section, String parameter, String status, String message) {

        public static Detail ok(String section, String parameter) {
            return new Detail(section, parameter, "OK", null);
        }

        public static Detail skipped(String section, String parameter, String message) {
            return new Detail(section, parameter, "SKIPPED", message);
        }

        public static Detail error(String section, String parameter, String message) {
            return new Detail(section, parameter, "ERROR", message);
        }
    }

    /** Có lỗi → FAILED; chỉ bỏ qua tham số không hỗ trợ → PARTIAL; còn lại SUCCESS (US-27 AC1). */
    public static Result summarize(List<Detail> details) {
        if (details.stream().anyMatch(d -> "ERROR".equals(d.status()))) {
            return Result.FAILED;
        }
        return details.stream().anyMatch(d -> "SKIPPED".equals(d.status())) ? Result.PARTIAL : Result.SUCCESS;
    }

    public boolean empty() {
        return recording == null && stream == null && image == null;
    }
}
