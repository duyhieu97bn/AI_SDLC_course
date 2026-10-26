package vn.camhub.access.application;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.camhub.access.api.AccessApi;
import vn.camhub.access.api.Permission;
import vn.camhub.access.api.ScopeFilter;
import vn.camhub.access.api.ScopeType;
import vn.camhub.access.domain.SystemRole;
import vn.camhub.access.infrastructure.AccessRepository;
import vn.camhub.access.infrastructure.AccessRepository.RoleRow;
import vn.camhub.access.infrastructure.PermissionCache;
import vn.camhub.platform.audit.AuditableEvent;
import vn.camhub.platform.security.ApiException;

/** Hiện thực {@link AccessApi}: điểm vào duy nhất của module khác vào module access. */
@Service
public class AccessFacade implements AccessApi {

    private final PermissionService permissions;
    private final AccessRepository repo;
    private final PermissionCache cache;
    private final ApplicationEventPublisher events;

    public AccessFacade(PermissionService permissions, AccessRepository repo, PermissionCache cache,
            ApplicationEventPublisher events) {
        this.permissions = permissions;
        this.repo = repo;
        this.cache = cache;
        this.events = events;
    }

    @Override
    public boolean canOnCamera(UUID userId, Permission permission, UUID cameraId) {
        return permissions.canOnCamera(userId, permission, cameraId);
    }

    @Override
    public boolean canOnSite(UUID userId, Permission permission, UUID siteId) {
        return permissions.canOnSite(userId, permission, siteId);
    }

    @Override
    public boolean hasAnywhere(UUID userId, Permission permission) {
        return permissions.hasAnywhere(userId, permission);
    }

    @Override
    public boolean hasOrganizationWide(UUID userId, Permission permission) {
        return permissions.hasOrganizationWide(userId, permission);
    }

    @Override
    public Set<UUID> usersWithPermissionOnCamera(Permission permission, UUID cameraId) {
        return repo.assignedUserIds().stream().filter(u -> permissions.canOnCamera(u, permission, cameraId))
                .collect(java.util.stream.Collectors.toSet());
    }

    @Override
    public Set<UUID> usersWithPermissionOnSite(Permission permission, UUID siteId,
            java.util.Collection<UUID> cameraIds) {
        return repo.assignedUserIds().stream()
                .filter(u -> permissions.canOnSite(u, permission, siteId)
                        || cameraIds.stream().anyMatch(c -> permissions.canOnCamera(u, permission, c)))
                .collect(java.util.stream.Collectors.toSet());
    }

    @Override
    public Set<UUID> usersWithOrganizationPermission(Permission permission) {
        return repo.assignedUserIds().stream().filter(u -> permissions.hasOrganizationWide(u, permission))
                .collect(java.util.stream.Collectors.toSet());
    }

    @Override
    public ScopeFilter scopeFilter(UUID userId, Set<Permission> perms) {
        return permissions.scopeFilter(userId, perms);
    }

    @Override
    public Set<Permission> effectivePermissions(UUID userId) {
        return permissions.effectivePermissions(userId);
    }

    @Override
    public List<RoleRef> rolesOf(UUID userId) {
        Set<UUID> roleIds = new HashSet<>();
        repo.assignmentsOf(userId).forEach(a -> roleIds.add(a.roleId()));
        return repo.roles().stream().filter(r -> roleIds.contains(r.id()))
                .map(r -> new RoleRef(r.id(), r.code(), r.name(), r.requireTwoFactor(), r.rank())).toList();
    }

    @Override
    public UserVisibility manageableUsers(UUID actorId) {
        if (permissions.hasOrganizationWide(actorId, Permission.MANAGE_USERS)) {
            return new UserVisibility(true, Set.of());
        }
        ScopeFilter f = permissions.scopeFilter(actorId, Set.of(Permission.MANAGE_USERS));
        if (f.isEmpty()) {
            return new UserVisibility(false, Set.of());
        }
        return new UserVisibility(false, repo.usersAssignedWithin(f.siteIds(), f.groupIds(), f.cameraIds()));
    }

    @Override
    @Transactional
    public void replaceAssignments(UUID actorId, UUID userId, List<AssignmentSpec> assignments) {
        if (!permissions.hasAnywhere(actorId, Permission.MANAGE_USERS)) {
            throw ApiException.forbidden();
        }
        // Gán cũ cũng phải nằm trong phạm vi người sửa, nếu không Site Manager gỡ được quyền ở địa điểm khác
        for (var existing : repo.assignmentsOf(userId)) {
            if ("MANUAL".equals(existing.source())
                    && !permissions.scopeWithin(actorId, Permission.MANAGE_USERS, existing.scopeType(), existing.scopeId())) {
                throw new ApiException(403, "ASSIGNMENT_OUT_OF_SCOPE",
                        "Người dùng có quyền ở phạm vi ngoài quyền quản lý của bạn");
            }
        }
        for (AssignmentSpec a : assignments) {
            validate(actorId, a);
        }
        repo.deleteManualAssignments(userId);
        for (AssignmentSpec a : assignments) {
            repo.insertAssignment(userId, a.roleId(), a.scopeType(), a.scopeId(), "MANUAL", actorId);
        }
        cache.invalidate(userId);
        events.publishEvent(AuditableEvent.of(actorId, "CHANGE_PERMISSION", "USER", userId.toString(),
                Map.of("assignments", assignments.size())));
    }

    /** INV-22, INV-27. */
    private void validate(UUID actorId, AssignmentSpec a) {
        if ((a.scopeType() == ScopeType.ORGANIZATION) != (a.scopeId() == null)) {
            throw ApiException.badRequest("INVALID_SCOPE", "Phạm vi không hợp lệ");
        }
        RoleRow role = repo.role(a.roleId()).orElseThrow(() -> ApiException.notFound("vai trò"));
        if (!permissions.scopeWithin(actorId, Permission.MANAGE_USERS, a.scopeType(), a.scopeId())) {
            throw new ApiException(403, "ASSIGNMENT_OUT_OF_SCOPE",
                    "Chỉ gán được phạm vi nằm trong phạm vi quản lý của bạn");
        }
        for (Permission p : role.permissions()) {
            if (!permissions.scopeWithin(actorId, p, a.scopeType(), a.scopeId())) {
                throw new ApiException(403, "ROLE_EXCEEDS_OWN_PERMISSIONS",
                        "Không gán được vai trò có quyền mà bạn không có: " + p, Map.of("permission", p.name()));
            }
        }
    }

    @Override
    @Transactional
    public UUID provisionSystemRoles(UUID organizationId) {
        UUID adminId = null;
        for (SystemRole r : SystemRole.values()) {
            UUID id = repo.insertRole(r.name(), r.displayName(), null, true, r.rank(), r.permissions());
            if (r == SystemRole.ADMIN) {
                adminId = id;
            }
        }
        return adminId;
    }

    @Override
    @Transactional
    public void assignUnchecked(UUID userId, UUID roleId, ScopeType scopeType, UUID scopeId, String source) {
        repo.insertAssignment(userId, roleId, scopeType, scopeId, source, null);
        cache.invalidate(userId);
    }

    @Override
    public void replaceDirectoryAssignments(UUID userId, List<AssignmentSpec> assignments) {
        repo.deleteDirectoryAssignments(userId);
        for (AssignmentSpec a : assignments) {
            repo.insertAssignment(userId, a.roleId(), a.scopeType(), a.scopeId(), "DIRECTORY", null);
        }
        cache.invalidate(userId);
    }
}
