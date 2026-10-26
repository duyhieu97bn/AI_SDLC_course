package vn.camhub.identity.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.camhub.access.api.AccessApi;
import vn.camhub.access.api.AccessApi.AssignmentSpec;
import vn.camhub.access.api.Permission;
import vn.camhub.identity.api.IdentityApi;
import vn.camhub.identity.domain.User;
import vn.camhub.identity.domain.User.UserStatus;
import vn.camhub.identity.infrastructure.UserRepository;
import vn.camhub.platform.audit.AuditableEvent;
import vn.camhub.platform.security.ApiException;

/** Quản lý người dùng (UC-03, UC-05). Site Manager chỉ thấy/tạo người dùng trong địa điểm của mình (INV-27). */
@Service
public class UserService implements IdentityApi {

    private final UserRepository users;
    private final PasswordHasher hasher;
    private final AccessApi access;
    private final AuthService auth;
    private final ApplicationEventPublisher events;

    public UserService(UserRepository users, PasswordHasher hasher, AccessApi access, AuthService auth,
            ApplicationEventPublisher events) {
        this.users = users;
        this.hasher = hasher;
        this.access = access;
        this.auth = auth;
        this.events = events;
    }

    public List<User> list(UUID actorId, String q) {
        AccessApi.UserVisibility v = access.manageableUsers(actorId);
        return users.list(v.all(), v.userIds(), actorId, q);
    }

    /** Hồ sơ của chính người dùng đang đăng nhập, không cần quyền quản lý. */
    public User self(UUID userId) {
        return users.find(userId).orElseThrow(() -> ApiException.notFound("người dùng"));
    }

    public User get(UUID actorId, UUID userId) {
        User u = users.find(userId).orElseThrow(() -> ApiException.notFound("người dùng"));
        requireManageable(actorId, u);
        return u;
    }

    @Transactional
    public User create(UUID actorId, String email, String displayName, String password, String language,
            List<AssignmentSpec> assignments) {
        if (!access.hasAnywhere(actorId, Permission.MANAGE_USERS)) {
            throw ApiException.forbidden();
        }
        // Site Manager phải gán ít nhất một phạm vi trong địa điểm của mình, nếu không người dùng mới "lạc" khỏi phạm vi quản lý
        if (assignments.isEmpty() && !access.hasOrganizationWide(actorId, Permission.MANAGE_USERS)) {
            throw ApiException.badRequest("ASSIGNMENT_REQUIRED", "Chọn ít nhất một vai trò và phạm vi");
        }
        PasswordHasher.validateStrength(password);
        if (users.lookupForLogin(email.trim()).isPresent()) {
            throw ApiException.conflict("EMAIL_EXISTS", "Email đã được dùng cho tài khoản khác");
        }
        UUID id = users.insertLocal(email, displayName, hasher.hash(password), language == null ? "vi" : language,
                actorId);
        access.replaceAssignments(actorId, id, assignments);
        events.publishEvent(AuditableEvent.of(actorId, "CHANGE_PERMISSION", "USER", id.toString(),
                Map.of("operation", "CREATE_USER", "email", email)));
        return users.find(id).orElseThrow();
    }

    @Transactional
    public User update(UUID actorId, UUID userId, String displayName, String language, UserStatus status) {
        User u = get(actorId, userId);
        users.updateProfile(userId, displayName, language);
        if (status != null && status != u.status()) {
            if (userId.equals(actorId)) {
                throw ApiException.badRequest("SELF_STATUS", "Không tự khóa hoặc vô hiệu hóa tài khoản của chính mình");
            }
            users.setStatus(userId, status);
            if (status != UserStatus.ACTIVE) {
                auth.revokeAll(userId, status.name());
            }
            events.publishEvent(AuditableEvent.of(actorId, "CHANGE_PERMISSION", "USER", userId.toString(),
                    Map.of("operation", "SET_STATUS", "status", status.name())));
        }
        return users.find(userId).orElseThrow();
    }

    @Transactional
    public void resetPassword(UUID actorId, UUID userId, String newPassword) {
        User u = get(actorId, userId);
        if (u.authSource() != User.AuthSource.LOCAL) {
            throw ApiException.badRequest("DIRECTORY_USER", "Mật khẩu của tài khoản thư mục do LDAP/AD quản lý");
        }
        PasswordHasher.validateStrength(newPassword);
        users.setPassword(userId, hasher.hash(newPassword));
        auth.revokeAll(userId, "PASSWORD_RESET");
        events.publishEvent(AuditableEvent.of(actorId, "CHANGE_PERMISSION", "USER", userId.toString(),
                Map.of("operation", "RESET_PASSWORD")));
    }

    @Transactional
    public void updateOwnProfile(UUID userId, String displayName, String language) {
        users.updateProfile(userId, displayName, language);
    }

    private void requireManageable(UUID actorId, User u) {
        AccessApi.UserVisibility v = access.manageableUsers(actorId);
        if (!v.all() && !v.userIds().contains(u.id()) && !actorId.equals(u.createdBy())) {
            throw ApiException.forbidden();
        }
    }

    // ---------------------------------------------------------------- IdentityApi

    @Override
    @Transactional
    public UUID createLocalUser(String email, String displayName, String password) {
        return users.insertLocal(email, displayName, hasher.hash(password), "vi", null);
    }

    @Override
    public java.util.Optional<String> displayName(UUID userId) {
        return users.find(userId).map(User::displayName);
    }

    @Override
    public java.util.Optional<String> email(UUID userId) {
        return users.find(userId).filter(u -> u.status() == UserStatus.ACTIVE).map(User::email);
    }
}
