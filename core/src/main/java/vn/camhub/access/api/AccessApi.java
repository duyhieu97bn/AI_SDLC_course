package vn.camhub.access.api;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** API công khai của module access cho các module khác. */
public interface AccessApi {

    /** INV-10: người dùng có {@code permission} trên camera (theo vị trí hiện tại của camera). */
    boolean canOnCamera(UUID userId, Permission permission, UUID cameraId);

    /** Có {@code permission} bao trùm địa điểm (phạm vi Tổ chức hoặc chính địa điểm đó). */
    boolean canOnSite(UUID userId, Permission permission, UUID siteId);

    /** Có {@code permission} ở bất kỳ phạm vi nào. */
    boolean hasAnywhere(UUID userId, Permission permission);

    /** Có {@code permission} ở phạm vi toàn Tổ chức. */
    boolean hasOrganizationWide(UUID userId, Permission permission);

    /** Người dùng (có gán vai trò) có {@code permission} trên camera: người nhận cảnh báo/thông báo. */
    Set<UUID> usersWithPermissionOnCamera(Permission permission, UUID cameraId);

    /** Người dùng có {@code permission} bao trùm địa điểm, hoặc trên ít nhất một camera trong {@code cameraIds}. */
    Set<UUID> usersWithPermissionOnSite(Permission permission, UUID siteId, java.util.Collection<UUID> cameraIds);

    /** Người dùng có {@code permission} ở phạm vi toàn Tổ chức. */
    Set<UUID> usersWithOrganizationPermission(Permission permission);

    /** Tập camera được thấy với ít nhất một trong các quyền. */
    ScopeFilter scopeFilter(UUID userId, Set<Permission> permissions);

    /** Mọi quyền người dùng có ở bất kỳ phạm vi nào (giao diện ẩn/hiện menu). */
    Set<Permission> effectivePermissions(UUID userId);

    /** Vai trò của người dùng (để chọn trang mặc định và kiểm tra 2FA bắt buộc). */
    List<RoleRef> rolesOf(UUID userId);

    record RoleRef(UUID id, String code, String name, boolean requireTwoFactor, int rank) {
    }

    /** Người dùng mà {@code actorId} được quản lý (MANAGE_USERS): tất cả, hoặc các id cụ thể. */
    record UserVisibility(boolean all, Set<UUID> userIds) {
    }

    UserVisibility manageableUsers(UUID actorId);

    /** Một gán vai trò; {@code scopeId} rỗng khi phạm vi là ORGANIZATION. */
    record AssignmentSpec(UUID roleId, ScopeType scopeType, UUID scopeId) {
    }

    /**
     * Thay toàn bộ gán vai trò thủ công của {@code userId}. Kiểm tra INV-22 và INV-27 với {@code actorId};
     * gán vai trò sinh từ LDAP (INV-26) không bị đụng tới.
     */
    void replaceAssignments(UUID actorId, UUID userId, List<AssignmentSpec> assignments);

    /** Tạo 5 vai trò hệ thống cho Tổ chức mới; trả về id vai trò Admin. */
    UUID provisionSystemRoles(UUID organizationId);

    /** Gán vai trò bỏ qua kiểm tra người gán (chỉ dùng khi khởi tạo Tổ chức và đồng bộ LDAP). */
    void assignUnchecked(UUID userId, UUID roleId, ScopeType scopeType, UUID scopeId, String source);

    /** Thay toàn bộ gán vai trò sinh từ ánh xạ nhóm thư mục (INV-26); gán thủ công giữ nguyên. */
    void replaceDirectoryAssignments(UUID userId, List<AssignmentSpec> assignments);
}
