package vn.camhub.organization.application;

import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.camhub.access.api.AccessApi;
import vn.camhub.access.api.Permission;
import vn.camhub.organization.api.CameraLocationPort;
import vn.camhub.organization.api.CameraLocationPort.Location;
import vn.camhub.organization.infrastructure.OrganizationRepository;
import vn.camhub.organization.infrastructure.OrganizationRepository.ZoneRow;
import vn.camhub.platform.audit.AuditableEvent;
import vn.camhub.platform.security.ApiException;

/**
 * Chuyển camera sang khu vực/địa điểm khác (US-05 AC3, RULE-07). Video, cảnh báo, giữ bằng chứng gắn với camera
 * nên không bị đụng tới (INV-18); quyền xem video cũ tính theo vị trí mới.
 */
@Service
public class MoveCameraService {

    private final CameraLocationPort cameras;
    private final OrganizationRepository repo;
    private final AccessApi access;
    private final ApplicationEventPublisher events;

    public MoveCameraService(CameraLocationPort cameras, OrganizationRepository repo, AccessApi access,
            ApplicationEventPublisher events) {
        this.cameras = cameras;
        this.repo = repo;
        this.access = access;
        this.events = events;
    }

    @Transactional
    public Location move(UUID actor, UUID cameraId, UUID targetZoneId) {
        Location from = cameras.findLocation(cameraId).orElseThrow(() -> ApiException.notFound("camera"));
        ZoneRow to = repo.zone(targetZoneId).orElseThrow(() -> ApiException.notFound("khu vực"));
        // Phải có quyền cấu hình ở cả nơi đi và nơi đến, nếu không Site Manager "đẩy" được camera sang địa điểm khác
        if (!access.canOnCamera(actor, Permission.CONFIGURE_CAMERA, cameraId)
                || !access.canOnSite(actor, Permission.CONFIGURE_CAMERA, to.siteId())) {
            throw ApiException.forbidden();
        }
        if (from.zoneId().equals(targetZoneId)) {
            return from;
        }
        cameras.relocate(cameraId, to.siteId(), targetZoneId);
        repo.recordMove(cameraId, to.siteId(), targetZoneId, actor);
        events.publishEvent(AuditableEvent.of(actor, "MOVE_CAMERA", "CAMERA", cameraId.toString(), Map.of(
                "fromSiteId", from.siteId().toString(), "fromZoneId", from.zoneId().toString(),
                "toSiteId", to.siteId().toString(), "toZoneId", targetZoneId.toString())));
        return new Location(cameraId, from.cameraName(), to.siteId(), targetZoneId);
    }
}
