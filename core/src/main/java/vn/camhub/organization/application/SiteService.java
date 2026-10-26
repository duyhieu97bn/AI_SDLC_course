package vn.camhub.organization.application;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.camhub.access.api.AccessApi;
import vn.camhub.access.api.Permission;
import vn.camhub.access.api.ScopeFilter;
import vn.camhub.organization.api.CameraLocationPort;
import vn.camhub.organization.api.OrganizationApi;
import vn.camhub.organization.infrastructure.OrganizationRepository;
import vn.camhub.organization.infrastructure.OrganizationRepository.SiteRow;
import vn.camhub.organization.infrastructure.OrganizationRepository.ZoneRow;
import vn.camhub.platform.audit.AuditableEvent;
import vn.camhub.platform.security.ApiException;

/** Cây Tổ chức → Địa điểm → Khu vực, và nhóm camera (US-05, UC-14). */
@Service
public class SiteService implements OrganizationApi {

    /** Quyền đủ để thấy một địa điểm trên cây. */
    static final Set<Permission> SEE = Set.of(Permission.VIEW_LIVE, Permission.VIEW_PLAYBACK,
            Permission.CONFIGURE_CAMERA, Permission.MANAGE_USERS);

    public record ZoneNode(UUID id, String name) {
    }

    public record SiteNode(UUID id, String name, String address, String timezone, List<ZoneNode> zones,
            boolean manageable) {
    }

    private final OrganizationRepository repo;
    private final AccessApi access;
    private final CameraLocationPort cameras;
    private final ApplicationEventPublisher events;

    public SiteService(OrganizationRepository repo, AccessApi access, CameraLocationPort cameras,
            ApplicationEventPublisher events) {
        this.repo = repo;
        this.access = access;
        this.cameras = cameras;
        this.events = events;
    }

    /** Chỉ địa điểm người dùng có phạm vi, hoặc chứa camera người dùng được thấy (US-32 AC1). */
    public List<SiteNode> tree(UUID userId) {
        ScopeFilter f = access.scopeFilter(userId, SEE);
        Set<UUID> visible = f.all() ? null : new java.util.HashSet<>(f.siteIds());
        if (visible != null && (!f.groupIds().isEmpty() || !f.cameraIds().isEmpty())) {
            visible.addAll(cameras.sitesWithCameras(new ScopeFilter(false, Set.of(), f.groupIds(), f.cameraIds())));
        }
        Map<UUID, List<ZoneNode>> zones = repo.zones().stream().collect(Collectors.groupingBy(ZoneRow::siteId,
                Collectors.mapping(z -> new ZoneNode(z.id(), z.name()), Collectors.toList())));
        return repo.sites().stream().filter(s -> visible == null || visible.contains(s.id()))
                .map(s -> new SiteNode(s.id(), s.name(), s.address(), s.timezone(), zones.getOrDefault(s.id(), List.of()),
                        access.canOnSite(userId, Permission.CONFIGURE_CAMERA, s.id())))
                .toList();
    }

    @Transactional
    public UUID createSite(UUID actor, String name, String address, String timezone) {
        requireOrgAdmin(actor);
        UUID id = repo.insertSite(name.trim(), address, timezone);
        audit(actor, "SITE", id, Map.of("operation", "CREATE_SITE", "name", name));
        return id;
    }

    @Transactional
    public void updateSite(UUID actor, UUID siteId, String name, String address, String timezone) {
        requireOrgAdmin(actor);
        if (repo.updateSite(siteId, name, address, timezone) == 0) {
            throw ApiException.notFound("địa điểm");
        }
        audit(actor, "SITE", siteId, Map.of("operation", "UPDATE_SITE"));
    }

    @Transactional
    public void deleteSite(UUID actor, UUID siteId) {
        requireOrgAdmin(actor);
        if (repo.zones().stream().anyMatch(z -> z.siteId().equals(siteId))) {
            throw ApiException.conflict("SITE_NOT_EMPTY", "Xóa hết khu vực của địa điểm trước");
        }
        repo.deleteSite(siteId);
        audit(actor, "SITE", siteId, Map.of("operation", "DELETE_SITE"));
    }

    @Transactional
    public UUID createZone(UUID actor, UUID siteId, String name) {
        requireSite(actor, siteId);
        UUID id = repo.insertZone(siteId, name.trim());
        audit(actor, "ZONE", id, Map.of("operation", "CREATE_ZONE", "siteId", siteId.toString(), "name", name));
        return id;
    }

    @Transactional
    public void renameZone(UUID actor, UUID zoneId, String name) {
        ZoneRow z = repo.zone(zoneId).orElseThrow(() -> ApiException.notFound("khu vực"));
        requireSite(actor, z.siteId());
        repo.renameZone(zoneId, name.trim());
        audit(actor, "ZONE", zoneId, Map.of("operation", "RENAME_ZONE"));
    }

    @Transactional
    public void deleteZone(UUID actor, UUID zoneId) {
        ZoneRow z = repo.zone(zoneId).orElseThrow(() -> ApiException.notFound("khu vực"));
        requireSite(actor, z.siteId());
        long n = cameras.countInZone(zoneId);
        if (n > 0) {
            throw new ApiException(409, "ZONE_NOT_EMPTY", "Khu vực còn " + n + " camera, chuyển camera đi trước",
                    Map.of("cameraCount", n));
        }
        repo.deleteZone(zoneId);
        audit(actor, "ZONE", zoneId, Map.of("operation", "DELETE_ZONE"));
    }

    private void requireOrgAdmin(UUID actor) {
        if (!access.hasOrganizationWide(actor, Permission.MANAGE_SYSTEM)) {
            throw ApiException.forbidden();
        }
    }

    private void requireSite(UUID actor, UUID siteId) {
        repo.site(siteId).orElseThrow(() -> ApiException.notFound("địa điểm"));
        if (!access.canOnSite(actor, Permission.CONFIGURE_CAMERA, siteId)) {
            throw ApiException.forbidden();
        }
    }

    private void audit(UUID actor, String type, UUID id, Map<String, Object> details) {
        events.publishEvent(AuditableEvent.of(actor, "CHANGE_CONFIG", type, id.toString(), details));
    }

    // ---------------------------------------------------------------- OrganizationApi

    @Override
    public Optional<UUID> zoneByName(String siteName, String zoneName) {
        if (siteName == null || zoneName == null) {
            return Optional.empty();
        }
        var sites = repo.sites().stream().filter(s -> s.name().trim().equalsIgnoreCase(siteName.trim()))
                .map(SiteRow::id).toList();
        return repo.zones().stream().filter(z -> sites.contains(z.siteId())
                && z.name().trim().equalsIgnoreCase(zoneName.trim())).map(ZoneRow::id).findFirst();
    }

    @Override
    public Optional<UUID> siteOfZone(UUID zoneId) {
        return repo.zone(zoneId).map(ZoneRow::siteId);
    }

    @Override
    public Optional<String> siteName(UUID siteId) {
        return repo.site(siteId).map(SiteRow::name);
    }

    @Override
    public String siteTimezone(UUID siteId) {
        return repo.site(siteId).map(SiteRow::timezone).orElse("Asia/Ho_Chi_Minh");
    }

    @Override
    public void recordInitialLocation(UUID cameraId, UUID siteId, UUID zoneId, UUID by) {
        repo.recordMove(cameraId, siteId, zoneId, by);
    }
}
