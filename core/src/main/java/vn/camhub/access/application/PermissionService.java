package vn.camhub.access.application;

import java.io.Serializable;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import vn.camhub.access.api.CameraScopeLookup;
import vn.camhub.access.api.Permission;
import vn.camhub.access.api.ScopeFilter;
import vn.camhub.access.api.ScopeType;
import vn.camhub.access.domain.Grant;
import vn.camhub.access.infrastructure.AccessRepository;
import vn.camhub.access.infrastructure.PermissionCache;
import vn.camhub.platform.security.CurrentUser;
import vn.camhub.platform.tenancy.OrganizationContext;

/**
 * Kiểm tra quyền theo phạm vi (INV-10, SADD 3.11.1). Cũng là {@link PermissionEvaluator} của Spring Security, nên
 * controller dùng được {@code @PreAuthorize("hasPermission(#id, 'CAMERA', 'VIEW_LIVE')")}. Kiểu đối tượng:
 * {@code CAMERA}, {@code SITE} (id địa điểm), {@code ANY} (bất kỳ phạm vi nào), {@code ORGANIZATION}.
 */
@Service
public class PermissionService implements PermissionEvaluator {

    private final AccessRepository repo;
    private final PermissionCache cache;
    private final CameraScopeLookup cameras;

    public PermissionService(AccessRepository repo, PermissionCache cache, CameraScopeLookup cameras) {
        this.repo = repo;
        this.cameras = cameras;
        this.cache = cache;
    }

    public List<Grant> grants(UUID userId) {
        UUID org = OrganizationContext.require();
        return cache.get(org, userId, () -> repo.grantsOf(userId));
    }

    public boolean canOnCamera(UUID userId, Permission p, UUID cameraId) {
        List<Grant> grants = grants(userId).stream().filter(g -> g.grants(p)).toList();
        if (grants.isEmpty()) {
            return false;
        }
        if (grants.stream().anyMatch(g -> g.scopeType() == ScopeType.ORGANIZATION)) {
            return true;
        }
        return cameras.find(cameraId)
                .map(c -> grants.stream().anyMatch(g -> g.covers(cameraId, c.siteId(), c.groupIds())))
                .orElse(false);
    }

    public boolean canOnSite(UUID userId, Permission p, UUID siteId) {
        return grants(userId).stream().filter(g -> g.grants(p)).anyMatch(g ->
                g.scopeType() == ScopeType.ORGANIZATION || (g.scopeType() == ScopeType.SITE && g.scopeId().equals(siteId)));
    }

    public boolean hasAnywhere(UUID userId, Permission p) {
        return grants(userId).stream().anyMatch(g -> g.grants(p));
    }

    public boolean hasOrganizationWide(UUID userId, Permission p) {
        return grants(userId).stream().anyMatch(g -> g.grants(p) && g.scopeType() == ScopeType.ORGANIZATION);
    }

    public ScopeFilter scopeFilter(UUID userId, Set<Permission> permissions) {
        Set<UUID> sites = new HashSet<>();
        Set<UUID> groups = new HashSet<>();
        Set<UUID> cams = new HashSet<>();
        for (Grant g : grants(userId)) {
            if (g.permissions().stream().noneMatch(permissions::contains)) {
                continue;
            }
            switch (g.scopeType()) {
                case ORGANIZATION -> {
                    return ScopeFilter.ALL;
                }
                case SITE -> sites.add(g.scopeId());
                case GROUP -> groups.add(g.scopeId());
                case CAMERA -> cams.add(g.scopeId());
            }
        }
        return new ScopeFilter(false, sites, groups, cams);
    }

    public Set<Permission> effectivePermissions(UUID userId) {
        Set<Permission> all = EnumSet.noneOf(Permission.class);
        grants(userId).forEach(g -> all.addAll(g.permissions()));
        return all;
    }

    /**
     * Phạm vi (của một gán vai trò) có nằm trong phạm vi mà người dùng có quyền {@code p} không. Dùng cho INV-27:
     * Site Manager chỉ gán được phạm vi trong địa điểm của mình.
     */
    public boolean scopeWithin(UUID userId, Permission p, ScopeType type, UUID scopeId) {
        List<Grant> grants = grants(userId).stream().filter(g -> g.grants(p)).toList();
        if (grants.stream().anyMatch(g -> g.scopeType() == ScopeType.ORGANIZATION)) {
            return true;
        }
        return switch (type) {
            case ORGANIZATION -> false;
            case SITE -> grants.stream().anyMatch(g -> g.scopeType() == ScopeType.SITE && g.scopeId().equals(scopeId));
            case CAMERA -> canOnCamera(userId, p, scopeId);
            case GROUP -> {
                if (grants.stream().anyMatch(g -> g.scopeType() == ScopeType.GROUP && g.scopeId().equals(scopeId))) {
                    yield true;
                }
                // Nhóm có thể trải nhiều địa điểm: chỉ hợp lệ khi mọi camera của nhóm nằm trong địa điểm được quản lý
                Set<UUID> groupSites = cameras.sitesOfGroup(scopeId);
                yield !groupSites.isEmpty() && groupSites.stream().allMatch(s -> canOnSite(userId, p, s));
            }
        };
    }

    // ---------------------------------------------------------------- PermissionEvaluator của Spring Security

    @Override
    public boolean hasPermission(Authentication auth, Object target, Object permission) {
        return hasPermission(auth, null, "ANY", permission);
    }

    @Override
    public boolean hasPermission(Authentication auth, Serializable targetId, String targetType, Object permission) {
        if (!(auth.getPrincipal() instanceof CurrentUser user)) {
            return false;
        }
        Permission p = Permission.valueOf(permission.toString());
        UUID id = targetId == null ? null : UUID.fromString(targetId.toString());
        return switch (targetType) {
            case "CAMERA" -> id != null && canOnCamera(user.userId(), p, id);
            case "SITE" -> id != null && canOnSite(user.userId(), p, id);
            case "ORGANIZATION" -> hasOrganizationWide(user.userId(), p);
            case "ANY" -> hasAnywhere(user.userId(), p);
            default -> false;
        };
    }
}
