package vn.camhub.organization.api;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import vn.camhub.access.api.ScopeFilter;

/**
 * Cổng để module organization đọc và đổi vị trí camera. Module device (chủ bảng camera) hiện thực,
 * nên organization không phụ thuộc device.
 */
public interface CameraLocationPort {

    record Location(UUID cameraId, String cameraName, UUID siteId, UUID zoneId) {
    }

    Optional<Location> findLocation(UUID cameraId);

    /** Đổi khu vực của camera; không đụng tới video, cảnh báo đã có (INV-18). */
    void relocate(UUID cameraId, UUID siteId, UUID zoneId);

    /** Địa điểm có ít nhất một camera nằm trong bộ lọc phạm vi. */
    Set<UUID> sitesWithCameras(ScopeFilter filter);

    long countInZone(UUID zoneId);
}
