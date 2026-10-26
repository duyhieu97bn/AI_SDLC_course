package vn.camhub.recording.domain;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Chính sách ghi hình của một camera (class diagram mục 4, US-16): liên tục, theo lịch tuần, theo sự kiện, hoặc tắt.
 * Theo sự kiện: giữ đoạn từ {@code preEventSeconds} trước đến {@code postEventSeconds} sau mỗi chuyển động.
 */
public record RecordingPolicy(UUID cameraId, Mode mode, List<Slot> schedule, int preEventSeconds,
        int postEventSeconds) {

    public enum Mode { CONTINUOUS, SCHEDULED, EVENT, OFF }

    /** Một khung giờ trong tuần; {@code day} 0 = Thứ Hai … 6 = Chủ nhật; {@code to} "24:00" là hết ngày. */
    public record Slot(int day, String from, String to) {
        public boolean contains(DayOfWeek d, LocalTime t) {
            if (d.getValue() - 1 != day) {
                return false;
            }
            LocalTime start = LocalTime.parse(from);
            return !t.isBefore(start) && ("24:00".equals(to) || t.isBefore(LocalTime.parse(to)));
        }

        public String validate() {
            if (day < 0 || day > 6) {
                return "Ngày trong tuần không hợp lệ";
            }
            try {
                LocalTime f = LocalTime.parse(from);
                if (!"24:00".equals(to) && !LocalTime.parse(to).isAfter(f)) {
                    return "Giờ kết thúc phải sau giờ bắt đầu";
                }
            } catch (RuntimeException e) {
                return "Giờ phải theo dạng HH:mm";
            }
            return null;
        }
    }

    public static RecordingPolicy off(UUID cameraId) {
        return new RecordingPolicy(cameraId, Mode.OFF, List.of(), 10, 30);
    }

    /** MediaMTX có cần ghi vào thời điểm {@code at} (giờ địa phương của địa điểm) không. */
    public boolean shouldRecord(ZonedDateTime at) {
        return switch (mode) {
            case CONTINUOUS, EVENT -> true; // theo sự kiện: ghi liên tục, job dọn chỉ giữ quanh mốc chuyển động
            case OFF -> false;
            case SCHEDULED -> schedule.stream().anyMatch(s -> s.contains(at.getDayOfWeek(), at.toLocalTime()));
        };
    }
}
