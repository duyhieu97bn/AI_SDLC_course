package vn.camhub.access.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import vn.camhub.access.application.RoleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.camhub.access.api.AccessApi;
import vn.camhub.access.api.AccessApi.AssignmentSpec;
import vn.camhub.access.api.Permission;
import vn.camhub.access.api.ScopeType;
import vn.camhub.access.infrastructure.AccessRepository;
import vn.camhub.platform.audit.AuditableEvent;
import vn.camhub.platform.security.ApiException;
import vn.camhub.platform.security.CurrentUser;

/** Vai trò và gán vai trò theo phạm vi (US-32, UC-03, UC-05). */
@RestController
@RequestMapping("/api/v1")
public class AccessController {

    public record RoleView(UUID id, String code, String name, String description, boolean system,
            boolean requireTwoFactor, int rank, Set<Permission> permissions, int assignedUsers) {
    }

    public record AssignmentView(UUID id, UUID roleId, String roleName, ScopeType scopeType, UUID scopeId,
            String source) {
    }

    public record AssignmentRequest(@NotNull UUID roleId, @NotNull ScopeType scopeType, UUID scopeId) {
    }

    private final AccessRepository repo;
    private final AccessApi access;
    private final ApplicationEventPublisher events;
    private final RoleService roles;

    public AccessController(AccessRepository repo, AccessApi access, ApplicationEventPublisher events,
            RoleService roles) {
        this.roles = roles;
        this.repo = repo;
        this.access = access;
        this.events = events;
    }

    @GetMapping("/roles")
    @PreAuthorize("hasPermission(null, 'ANY', 'MANAGE_USERS') or hasPermission(null, 'ANY', 'MANAGE_ROLES')")
    public List<RoleView> roles() {
        return repo.roles().stream().map(r -> new RoleView(r.id(), r.code(), r.name(), r.description(), r.system(),
                r.requireTwoFactor(), r.rank(), r.permissions(), r.assignedUsers())).toList();
    }

    @GetMapping("/users/{userId}/assignments")
    @PreAuthorize("hasPermission(null, 'ANY', 'MANAGE_USERS')")
    public List<AssignmentView> assignments(@PathVariable UUID userId) {
        requireManageable(userId);
        return repo.assignmentsOf(userId).stream().map(a -> new AssignmentView(a.id(), a.roleId(), a.roleName(),
                a.scopeType(), a.scopeId(), a.source())).toList();
    }

    @PutMapping("/users/{userId}/assignments")
    @PreAuthorize("hasPermission(null, 'ANY', 'MANAGE_USERS')")
    public List<AssignmentView> replace(@PathVariable UUID userId, @Valid @RequestBody List<AssignmentRequest> body) {
        requireManageable(userId);
        access.replaceAssignments(CurrentUser.require().userId(), userId,
                body.stream().map(a -> new AssignmentSpec(a.roleId(), a.scopeType(), a.scopeId())).toList());
        return assignments(userId);
    }

    /** Quyền của chính mình, để giao diện ẩn/hiện chức năng. */
    @GetMapping("/me/permissions")
    public Set<Permission> myPermissions() {
        return access.effectivePermissions(CurrentUser.require().userId());
    }

    public record TwoFactorRequest(boolean required) {
    }

    /** {@code copyFrom}: sao chép từ vai trò khác (US-43 AC1). */
    public record RoleRequest(String name, String description, Set<Permission> permissions, UUID copyFrom) {
    }

    @PostMapping("/roles")
    @ResponseStatus(HttpStatus.CREATED)
    public RoleView createRole(@RequestBody RoleRequest body) {
        return view(roles.create(CurrentUser.require().userId(), body.name(), body.description(),
                body.permissions() == null ? Set.of() : body.permissions(), body.copyFrom()));
    }

    @PutMapping("/roles/{roleId}")
    public RoleView updateRole(@PathVariable UUID roleId, @RequestBody RoleRequest body) {
        return view(roles.update(CurrentUser.require().userId(), roleId, body.name(), body.description(),
                body.permissions() == null ? Set.of() : body.permissions()));
    }

    @DeleteMapping("/roles/{roleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRole(@PathVariable UUID roleId) {
        roles.delete(CurrentUser.require().userId(), roleId);
    }

    private static RoleView view(AccessRepository.RoleRow r) {
        return new RoleView(r.id(), r.code(), r.name(), r.description(), r.system(), r.requireTwoFactor(), r.rank(),
                r.permissions(), r.assignedUsers());
    }

    /**
     * Bật/tắt bắt buộc 2FA cho vai trò (US-31 AC1, design/system.html mục Bảo mật). Áp dụng cả vai trò hệ thống:
     * đây là thiết lập bảo mật, không phải sửa tập quyền (INV-20 vẫn giữ).
     */
    @PatchMapping("/roles/{roleId}/two-factor")
    @PreAuthorize("hasPermission(null, 'ORGANIZATION', 'MANAGE_ROLES') or hasPermission(null, 'ORGANIZATION', 'MANAGE_SYSTEM')")
    public RoleView setTwoFactor(@PathVariable UUID roleId, @RequestBody TwoFactorRequest body) {
        repo.role(roleId).orElseThrow(() -> ApiException.notFound("vai trò"));
        repo.setRequireTwoFactor(roleId, body.required());
        events.publishEvent(AuditableEvent.of(CurrentUser.require().userId(), "CHANGE_ROLE", "ROLE",
                roleId.toString(), Map.of("requireTwoFactor", body.required())));
        var r = repo.role(roleId).orElseThrow();
        return new RoleView(r.id(), r.code(), r.name(), r.description(), r.system(), r.requireTwoFactor(), r.rank(),
                r.permissions(), r.assignedUsers());
    }

    public record ManageScope(boolean all) {
    }

    /** Người dùng có MANAGE_USERS ở phạm vi Tổ chức không (giao diện hiện lựa chọn phạm vi "Toàn Tổ chức"). */
    @GetMapping("/me/manage-scope")
    public ManageScope manageScope() {
        return new ManageScope(access.hasOrganizationWide(CurrentUser.require().userId(), Permission.MANAGE_USERS));
    }

    private void requireManageable(UUID userId) {
        var v = access.manageableUsers(CurrentUser.require().userId());
        // Người dùng chưa có gán nào chỉ hiện với người tạo; identity kiểm tra phần đó khi liệt kê
        if (!v.all() && !v.userIds().contains(userId) && !repo.assignmentsOf(userId).isEmpty()) {
            throw ApiException.forbidden();
        }
    }
}
