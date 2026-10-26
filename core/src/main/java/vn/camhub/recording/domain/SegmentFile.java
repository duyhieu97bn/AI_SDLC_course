package vn.camhub.recording.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Đọc thông tin đoạn ghi MediaMTX báo qua hook: tên file theo {@code recordPath}
 * {@code /recordings/%path/%Y-%m-%d_%H-%M-%S-%f} (giờ UTC của container), thời lượng dạng Go ("1m0.5s").
 */
public final class SegmentFile {

    private static final Pattern NAME = Pattern.compile("(\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2})-(\\d{1,6})\\.mp4$");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final Pattern GO_DURATION = Pattern.compile("(\\d+(?:\\.\\d+)?)(h|ms|m|s|us|µs|ns)");

    private SegmentFile() {
    }

    public static Optional<Instant> startOf(String file) {
        Matcher m = NAME.matcher(file == null ? "" : file);
        if (!m.find()) {
            return Optional.empty();
        }
        Instant base = LocalDateTime.parse(m.group(1), FMT).toInstant(ZoneOffset.UTC);
        String frac = (m.group(2) + "000000").substring(0, 6);
        return Optional.of(base.plusNanos(Long.parseLong(frac) * 1000));
    }

    public static Duration parseGoDuration(String s) {
        if (s == null || s.isBlank()) {
            return Duration.ZERO;
        }
        Matcher m = GO_DURATION.matcher(s);
        double nanos = 0;
        while (m.find()) {
            double v = Double.parseDouble(m.group(1));
            nanos += switch (m.group(2)) {
                case "h" -> v * 3_600e9;
                case "m" -> v * 60e9;
                case "s" -> v * 1e9;
                case "ms" -> v * 1e6;
                case "us", "µs" -> v * 1e3;
                default -> v;
            };
        }
        return Duration.ofNanos((long) nanos);
    }
}
