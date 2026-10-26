package vn.camhub.device.domain;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Camera đã thêm vào hệ thống (class diagram mục 3). {@code streamUrl} không chứa tài khoản; mật khẩu chỉ ở dạng
 * mã hóa trong DB và không có trong record này. URL camera không bao giờ trả ra trình duyệt (SADD 4).
 *
 * @param mediaPath    tên path trên MediaMTX (luồng phụ: {@code mediaPath + "-sub"})
 * @param capabilities JSON DeviceCapabilities do Device Gateway đọc được
 */
public record Camera(
        UUID id,
        UUID siteId,
        UUID zoneId,
        String name,
        String protocol,
        String ipAddress,
        Integer port,
        String streamUrl,
        String manufacturer,
        String model,
        String macAddress,
        String status,
        Instant lastOfflineAt,
        Instant createdAt,
        Set<UUID> groupIds,
        String username,
        String mediaPath,
        String capabilities,
        String firmware,
        boolean verified,
        Instant statusChangedAt,
        // Kênh của đầu ghi (US-03); null nếu là camera độc lập
        UUID recorderId,
        Integer channelNo,
        // Bộ kết nối địa điểm (ADR-10): camera sau NAT, truy cập qua đường hầm
        UUID connectorId) {

    public Camera {
        groupIds = Set.copyOf(groupIds);
    }

    public String subPath() {
        return mediaPath + "-sub";
    }
}
