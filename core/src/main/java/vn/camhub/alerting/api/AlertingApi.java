package vn.camhub.alerting.api;

import java.util.UUID;

/** API công khai của module alerting (gọi trong ngữ cảnh Tổ chức). */
public interface AlertingApi {

    /**
     * Kết quả đo một ổ lưu trữ (US-35). Module alerting so với ngưỡng của quy tắc STORAGE_THRESHOLD của Tổ chức,
     * tạo/nâng mức cảnh báo khi vượt và tự chuyển "Đã phục hồi" khi xuống dưới ngưỡng.
     */
    record StorageReading(String volume, long totalBytes, long usedBytes, double usedPercent, Double daysLeft) {
    }

    void onStorageMeasured(StorageReading reading);

    /** Số cảnh báo đang mở (Mới, Đang xử lý) mà người dùng được thấy. */
    long openCount(UUID userId);
}
