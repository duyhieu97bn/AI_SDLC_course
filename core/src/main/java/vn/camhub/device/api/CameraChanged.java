package vn.camhub.device.api;

import java.util.UUID;

/**
 * Sự kiện trong tiến trình (ApplicationEvent) khi camera được thêm, xóa, hoặc đổi trạng thái kết nối.
 * Module khác lắng nghe qua kiểu này (ví dụ alerting ở S5 sinh cảnh báo mất kết nối).
 */
public record CameraChanged(UUID organizationId, UUID cameraId, Kind kind) {

    public enum Kind { ADDED, REMOVED, ONLINE, OFFLINE }
}
