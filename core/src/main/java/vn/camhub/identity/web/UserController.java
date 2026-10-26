package vn.camhub.identity.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.camhub.access.api.AccessApi;
import vn.camhub.access.api.AccessApi.AssignmentSpec;
import vn.camhub.access.api.Permission;
import vn.camhub.access.api.ScopeType;
import vn.camhub.identity.application.UserService;
import vn.camhub.identity.domain.User;
import vn.camhub.identity.domain.User.UserStatus;
import vn.camhub.platform.security.CurrentUser;

/** Người dùng (US-32, users.html) và hồ sơ của chính mình (US-41: đổi ngôn ngữ). */
@RestController
@RequestMapping("/api/v1")
public class UserController {

    public record UserView(UUID id, String email, String displayName, String authSource, UserStatus status,
            String language, boolean twoFactorEnabled, Instant lastLoginAt, Instant createdAt) {
        static UserView of(User u) {
            return new UserView(u.id(), u.email(), u.displayName(), u.authSource().name(), u.status(), u.language(),
                    u.twoFactorEnabled(), u.lastLoginAt(), u.createdAt());
        }
    }

    public record Assignment(@NotNull UUID roleId, @NotNull ScopeType scopeType, UUID scopeId) {
    }

    public record CreateUser(@NotBlank @Email String email, @NotBlank @Size(max = 200) String displayName,
            @NotBlank String password, @Pattern(regexp = "vi|en") String language, List<@Valid Assignment> assignments) {
    }

    public record UpdateUser(@Size(max = 200) String displayName, @Pattern(regexp = "vi|en") String language,
            UserStatus status) {
    }

    public record ResetPassword(@NotBlank String password) {
    }

    public record Me(UUID id, String email, String displayName, String language, Set<Permission> permissions,
            List<String> roles) {
    }

    public record UpdateMe(@Size(max = 200) String displayName, @Pattern(regexp = "vi|en") String language) {
    }

    private final UserService users;
    private final AccessApi access;

    public UserController(UserService users, AccessApi access) {
        this.users = users;
        this.access = access;
    }

    @GetMapping("/users")
    @PreAuthorize("hasPermission(null, 'ANY', 'MANAGE_USERS')")
    public List<UserView> list(@RequestParam(required = false) String q) {
        return users.list(me(), q).stream().map(UserView::of).toList();
    }

    @GetMapping("/users/{id}")
    @PreAuthorize("hasPermission(null, 'ANY', 'MANAGE_USERS')")
    public UserView get(@PathVariable UUID id) {
        return UserView.of(users.get(me(), id));
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasPermission(null, 'ANY', 'MANAGE_USERS')")
    public UserView create(@Valid @RequestBody CreateUser req) {
        List<AssignmentSpec> specs = req.assignments() == null ? List.of() : req.assignments().stream()
                .map(a -> new AssignmentSpec(a.roleId(), a.scopeType(), a.scopeId())).toList();
        return UserView.of(users.create(me(), req.email(), req.displayName(), req.password(), req.language(), specs));
    }

    @PatchMapping("/users/{id}")
    @PreAuthorize("hasPermission(null, 'ANY', 'MANAGE_USERS')")
    public UserView update(@PathVariable UUID id, @Valid @RequestBody UpdateUser req) {
        return UserView.of(users.update(me(), id, req.displayName(), req.language(), req.status()));
    }

    @PostMapping("/users/{id}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasPermission(null, 'ANY', 'MANAGE_USERS')")
    public void resetPassword(@PathVariable UUID id, @Valid @RequestBody ResetPassword req) {
        users.resetPassword(me(), id, req.password());
    }

    @GetMapping("/me")
    public Me profile() {
        UUID id = me();
        User u = users.self(id);
        return new Me(u.id(), u.email(), u.displayName(), u.language(), access.effectivePermissions(id),
                access.rolesOf(id).stream().map(AccessApi.RoleRef::name).toList());
    }

    @PatchMapping("/me")
    public Me updateProfile(@Valid @RequestBody UpdateMe req) {
        users.updateOwnProfile(me(), req.displayName(), req.language());
        return profile();
    }

    private static UUID me() {
        return CurrentUser.require().userId();
    }
}
