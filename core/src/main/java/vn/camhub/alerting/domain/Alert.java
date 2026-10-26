package vn.camhub.alerting.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Cảnh báo (class diagram mục 7, UC-50..55). */
public record Alert(UUID id, AlertType type, Severity severity, Status status, String sourceKey, UUID cameraId,
        UUID siteId, String title, Map<String, Object> details, Instant occurredAt, Instant recoveredAt,
        UUID assigneeId, Instant closedAt, String outcome, String outcomeNote, String snapshotStatus) {

    public enum AlertType { CAMERA_OFFLINE, SITE_OFFLINE, STORAGE_THRESHOLD, MOTION }

    /** Kết quả bắt buộc khi đóng cảnh báo (US-37 AC3, INV-14). */
    public enum Outcome { RESOLVED, FALSE_ALARM, ESCALATED, NO_ACTION_NEEDED }

    public enum Severity { INFO, WARNING, CRITICAL }

    /** NEW → IN_PROGRESS → CLOSED; nguồn tự hết lỗi thì RECOVERED (US-34 AC3). */
    public enum Status {
        NEW, IN_PROGRESS, RECOVERED, CLOSED;

        public static final Set<Status> OPEN = Set.of(NEW, IN_PROGRESS);

        public boolean isOpen() {
            return OPEN.contains(this);
        }
    }

    public static String cameraKey(UUID cameraId) {
        return "camera:" + cameraId;
    }

    public static String siteKey(UUID siteId) {
        return "site:" + siteId;
    }

    /** Mỗi lần chuyển động là một cảnh báo riêng (chống spam theo thời gian chờ của quy tắc). */
    public static String motionKey(UUID cameraId, UUID ruleId, Instant at) {
        return "motion:" + cameraId + ":" + ruleId + ":" + at.toEpochMilli();
    }

    public static String storageKey(String volume) {
        return "storage:" + volume;
    }
}
