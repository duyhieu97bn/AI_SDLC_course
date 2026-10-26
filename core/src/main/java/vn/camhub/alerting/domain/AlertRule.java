package vn.camhub.alerting.domain;

import java.util.Map;
import java.util.Set;
import vn.camhub.alerting.domain.Alert.AlertType;

/**
 * Quy tắc cảnh báo của Tổ chức. Tham số: {@code offlineDelayMinutes} (mặc định 5) cho mất kết nối;
 * {@code warningPercent}/{@code criticalPercent} (mặc định 85/95) cho lưu trữ.
 */
public record AlertRule(AlertType type, boolean enabled, Map<String, Object> params, Set<String> channels) {

    public static final int DEFAULT_OFFLINE_DELAY = 5;
    public static final int DEFAULT_WARNING = 85;
    public static final int DEFAULT_CRITICAL = 95;

    public static AlertRule defaults(AlertType type) {
        Map<String, Object> params = switch (type) {
            case CAMERA_OFFLINE, SITE_OFFLINE -> Map.of("offlineDelayMinutes", DEFAULT_OFFLINE_DELAY);
            case STORAGE_THRESHOLD -> Map.of("warningPercent", DEFAULT_WARNING, "criticalPercent", DEFAULT_CRITICAL);
            case MOTION -> Map.of(); // quy tắc chuyển động có bảng riêng (MotionRule)
        };
        return new AlertRule(type, true, params, Set.of("WEB", "EMAIL"));
    }

    public int intParam(String name, int fallback) {
        Object v = params.get(name);
        return v instanceof Number n ? n.intValue() : fallback;
    }

    public int offlineDelayMinutes() {
        return intParam("offlineDelayMinutes", DEFAULT_OFFLINE_DELAY);
    }

    public int warningPercent() {
        return intParam("warningPercent", DEFAULT_WARNING);
    }

    public int criticalPercent() {
        return intParam("criticalPercent", DEFAULT_CRITICAL);
    }

    /** Ràng buộc tham số: trễ 1–1440 phút; 1 ≤ ngưỡng cảnh báo < ngưỡng nghiêm trọng ≤ 100. */
    public boolean valid() {
        return switch (type) {
            case CAMERA_OFFLINE, SITE_OFFLINE -> offlineDelayMinutes() >= 1 && offlineDelayMinutes() <= 1440;
            case STORAGE_THRESHOLD -> warningPercent() >= 1 && warningPercent() < criticalPercent()
                    && criticalPercent() <= 100;
            case MOTION -> false;
        };
    }
}
