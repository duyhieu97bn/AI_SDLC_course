package vn.camhub.alerting.domain;

import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Quy tắc cảnh báo chuyển động (US-36, UC-51): phạm vi (Tổ chức/địa điểm/khu vực/camera), lịch theo giờ địa phương của
 * địa điểm (khung qua nửa đêm như 22:00–06:00 được hỗ trợ), chống spam theo camera, kênh gửi.
 */
public record MotionRule(UUID id, String name, boolean enabled, ScopeType scopeType, UUID scopeId, List<Slot> schedule,
        int cooldownSeconds, Set<String> channels, boolean includeSnapshot) {

    public enum ScopeType { ORGANIZATION, SITE, ZONE, CAMERA }

    /** {@code days}: 1 = Thứ Hai … 7 = Chủ nhật (ISO); khung kết thúc trước giờ bắt đầu là qua nửa đêm. */
    public record Slot(List<Integer> days, String from, String to) {

        boolean covers(ZonedDateTime t) {
            LocalTime start = LocalTime.parse(from);
            LocalTime end = LocalTime.parse(to);
            LocalTime now = t.toLocalTime();
            int today = t.getDayOfWeek().getValue();
            if (!end.isAfter(start)) {
                // Qua nửa đêm: phần tối thuộc ngày bắt đầu, phần sáng thuộc ngày hôm sau
                int yesterday = t.getDayOfWeek().minus(1).getValue();
                return (!now.isBefore(start) && days.contains(today)) || (now.isBefore(end) && days.contains(yesterday));
            }
            return days.contains(today) && !now.isBefore(start) && now.isBefore(end);
        }

        public boolean valid() {
            try {
                LocalTime.parse(from);
                LocalTime.parse(to);
            } catch (RuntimeException e) {
                return false;
            }
            return days != null && !days.isEmpty() && days.stream().allMatch(d -> d != null && d >= 1 && d <= 7);
        }
    }

    /** Lịch rỗng = luôn áp dụng. */
    public boolean activeAt(ZonedDateTime localTime) {
        return enabled && (schedule == null || schedule.isEmpty() || schedule.stream().anyMatch(s -> s.covers(localTime)));
    }

    public boolean covers(UUID siteId, UUID zoneId, UUID cameraId) {
        return switch (scopeType) {
            case ORGANIZATION -> true;
            case SITE -> scopeId.equals(siteId);
            case ZONE -> scopeId.equals(zoneId);
            case CAMERA -> scopeId.equals(cameraId);
        };
    }
}
