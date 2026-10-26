package vn.camhub.organization.api;

import java.util.Optional;
import java.util.UUID;

/** API công khai của module organization. */
public interface OrganizationApi {

    /** Địa điểm chứa khu vực (dùng khi thêm camera: site_id luôn là địa điểm của khu vực, INV-02). */
    Optional<UUID> siteOfZone(UUID zoneId);

    Optional<String> siteName(UUID siteId);

    /** Khu vực theo tên địa điểm và tên khu vực (không phân biệt hoa thường) – nhập camera từ tệp (US-07). */
    Optional<UUID> zoneByName(String siteName, String zoneName);

    /** Múi giờ của địa điểm (lịch ghi, lịch cảnh báo, ngày trên lịch xem lại); mặc định Asia/Ho_Chi_Minh. */
    String siteTimezone(UUID siteId);

    /** Mở bản ghi vị trí đầu tiên của camera mới thêm (INV-19). */
    void recordInitialLocation(UUID cameraId, UUID siteId, UUID zoneId, UUID by);
}
