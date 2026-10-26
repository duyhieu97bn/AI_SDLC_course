package vn.camhub.recording.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** API công khai của module recording cho export, tổng quan hệ thống (gọi trong ngữ cảnh Tổ chức). */
public interface RecordingApi {

    /** Camera có ít nhất một đoạn ghi giao với [from, to). */
    boolean hasRecording(UUID cameraId, Instant from, Instant to);

    /**
     * Ổ lưu trữ ghi hình (US-35, US-39).
     *
     * @param daysLeft số ngày ước tính trước khi đầy theo tốc độ tăng 24 giờ gần nhất; null khi chưa đủ dữ liệu
     *                 hoặc dung lượng không tăng
     */
    record StorageVolume(String name, long totalBytes, long usedBytes, double usedPercent, Double daysLeft,
            Instant measuredAt) {
    }

    List<StorageVolume> storageVolumes();

    /** Khung giờ ghi theo lịch; {@code day} 0 = Thứ Hai … 6 = Chủ nhật. */
    record SlotSpec(int day, String from, String to) {
    }

    /** Chính sách ghi dùng cho cấu hình mẫu (US-27); {@code mode} CONTINUOUS, SCHEDULED, EVENT, OFF. */
    record PolicySpec(String mode, List<SlotSpec> schedule, int preEventSeconds, int postEventSeconds) {
    }

    /** Áp chính sách ghi cho camera thay người dùng {@code actor} (kiểm tra CONFIGURE_CAMERA, ghi nhật ký). */
    void applyPolicy(UUID actor, UUID cameraId, PolicySpec spec);
}
