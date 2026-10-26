package vn.camhub.access.application;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import vn.camhub.access.api.Permission;
import vn.camhub.access.infrastructure.AccessRepository;
import vn.camhub.access.infrastructure.AccessRepository.RoleRow;
import vn.camhub.access.infrastructure.PermissionCache;
import vn.camhub.platform.audit.AuditableEvent;
import vn.camhub.platform.security.ApiException;

/**
 * Vai trò tùy chỉnh (US-43, UC-22): tạo mới hoặc sao chép, sửa tên/mô tả/quyền, xóa. Cần MANAGE_ROLES toàn Tổ chức.
 * INV-20: vai trò hệ thống không sửa/đổi tên/xóa được. INV-21: không xóa vai trò đang gán. INV-22: không tạo vai trò
 * có quyền mà chính người tạo không có.
 */
@Service
public class RoleService {

    static final int DEFAULT_RANK = 30;

    private final AccessRepository repo;
    private final PermissionService permissions;
    private final PermissionCache cache;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;

    public RoleService(AccessRepository repo, PermissionService permissions, PermissionCache cache,
            TransactionTemplate tx, ApplicationEventPublisher events) {
        this.repo = repo;
        this.permissions = permissions;
        this.cache = cache;
        this.tx = tx;
        this.events = events;
    }

    /** Tạo vai trò; {@code copyFrom} khác null thì lấy thứ hạng của vai trò gốc (khóa PTZ theo thứ hạng). */
    public RoleRow create(UUID actor, String name, String description, Set<Permission> perms, UUID copyFrom) {
        require(actor);
        String n = validName(name);
        checkWithinOwn(actor, perms);
        int rank = copyFrom == null ? DEFAULT_RANK
                : repo.role(copyFrom).map(RoleRow::rank).orElseThrow(() -> ApiException.notFound("vai trò"));
        UUID id;
        try {
            id = tx.execute(s -> {
                UUID created = repo.insertRole(null, n, blankToNull(description), false, Math.min(rank, 99), perms);
                events.publishEvent(AuditableEvent.of(actor, "CHANGE_ROLE", "ROLE", created.toString(),
                        Map.of("operation", "CREATE_ROLE", "targetName", n, "permissions", perms.toString(),
                                "copyFrom", copyFrom == null ? "" : copyFrom.toString())));
                return created;
            });
        } catch (DuplicateKeyException e) {
            throw new ApiException(409, "DUPLICATE", "Đã có vai trò tên \"" + n + "\"");
        }
        return repo.role(id).orElseThrow();
    }

    public RoleRow update(UUID actor, UUID id, String name, String description, Set<Permission> perms) {
        require(actor);
        RoleRow r = repo.role(id).orElseThrow(() -> ApiException.notFound("vai trò"));
        if (r.system()) {
            throw new ApiException(409, "SYSTEM_ROLE", "Vai trò hệ thống không sửa được"); // INV-20
        }
        String n = validName(name);
        checkWithinOwn(actor, perms);
        try {
            tx.executeWithoutResult(s -> {
                repo.updateRole(id, n, blankToNull(description));
                repo.setPermissions(id, perms);
                events.publishEvent(AuditableEvent.of(actor, "CHANGE_ROLE", "ROLE", id.toString(), Map.of(
                        "operation", "UPDATE_ROLE", "targetName", n, "from", r.permissions().toString(),
                        "to", perms.toString())));
            });
        } catch (DuplicateKeyException e) {
            throw new ApiException(409, "DUPLICATE", "Đã có vai trò tên \"" + n + "\"");
        }
        // Quyền đổi có hiệu lực ngay với người đang được gán vai trò
        repo.usersWithRole(id).forEach(cache::invalidate);
        return repo.role(id).orElseThrow();
    }

    public void delete(UUID actor, UUID id) {
        require(actor);
        RoleRow r = repo.role(id).orElseThrow(() -> ApiException.notFound("vai trò"));
        if (r.system()) {
            throw new ApiException(409, "SYSTEM_ROLE", "Vai trò hệ thống không xóa được");
        }
        if (r.assignedUsers() > 0) {
            // INV-21: báo số người đang dùng (US-43 AC2)
            throw new ApiException(409, "ROLE_IN_USE", "Vai trò đang gán cho " + r.assignedUsers() + " người dùng",
                    Map.of("assignedUsers", r.assignedUsers()));
        }
        tx.executeWithoutResult(s -> {
            repo.deleteRole(id);
            events.publishEvent(AuditableEvent.of(actor, "CHANGE_ROLE", "ROLE", id.toString(),
                    Map.of("operation", "DELETE_ROLE", "targetName", r.name())));
        });
    }

    private void require(UUID actor) {
        if (!permissions.hasOrganizationWide(actor, Permission.MANAGE_ROLES)) {
            throw ApiException.forbidden();
        }
    }

    private void checkWithinOwn(UUID actor, Set<Permission> perms) {
        if (perms == null || perms.isEmpty()) {
            throw ApiException.badRequest("PERMISSIONS_REQUIRED", "Chọn ít nhất một quyền");
        }
        Set<Permission> own = EnumSet.noneOf(Permission.class);
        for (Permission p : Permission.values()) {
            if (permissions.hasOrganizationWide(actor, p)) {
                own.add(p);
            }
        }
        if (!own.containsAll(perms)) {
            throw new ApiException(403, "ROLE_EXCEEDS_OWN_PERMISSIONS", "Không thể tạo vai trò có quyền bạn không có");
        }
    }

    private static String validName(String name) {
        if (name == null || name.isBlank() || name.trim().length() > 100) {
            throw ApiException.badRequest("ROLE_NAME_REQUIRED", "Nhập tên vai trò (tối đa 100 ký tự)");
        }
        return name.trim();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
