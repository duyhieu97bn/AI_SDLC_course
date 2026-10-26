package vn.camhub.identity.application;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import vn.camhub.access.api.AccessApi;
import vn.camhub.access.api.AccessApi.AssignmentSpec;
import vn.camhub.access.api.Permission;
import vn.camhub.access.api.ScopeType;
import vn.camhub.identity.domain.User.UserStatus;
import vn.camhub.identity.infrastructure.DirectoryRepository;
import vn.camhub.identity.infrastructure.DirectoryRepository.Connection;
import vn.camhub.identity.infrastructure.DirectoryRepository.Mapping;
import vn.camhub.identity.infrastructure.LdapAuthenticator;
import vn.camhub.identity.infrastructure.LdapAuthenticator.DirectoryUser;
import vn.camhub.identity.infrastructure.SessionRepository;
import vn.camhub.identity.infrastructure.UserRepository;
import vn.camhub.platform.audit.AuditableEvent;
import vn.camhub.platform.crypto.CryptoService;
import vn.camhub.platform.security.ApiException;
import vn.camhub.platform.tenancy.OrganizationContext;

/**
 * Đăng nhập LDAP/AD (US-44, UC-04): cấu hình kết nối và ánh xạ nhóm → vai trò; đăng nhập lần đầu tạo tài khoản nguồn
 * DIRECTORY với vai trò theo nhóm (AC1); đồng bộ mỗi giờ cập nhật tên/email/vai trò và vô hiệu hóa tài khoản bị khóa
 * hoặc xóa trên thư mục (AC2). Gán vai trò thủ công không bị đụng tới (INV-26).
 */
@Service
public class DirectoryService {

    private static final Logger log = LoggerFactory.getLogger(DirectoryService.class);

    public record ConnectionView(boolean enabled, String url, String bindDn, boolean hasBindPassword, String userBase,
            String userFilter, String groupBase, String memberAttribute, String displayNameAttribute,
            String mailAttribute, String disabledGroupDn, java.time.Instant lastSyncAt, String lastSyncStatus,
            String lastSyncMessage) {
    }

    public record SyncResult(int checked, int updated, int disabled, String message) {
    }

    private final DirectoryRepository repo;
    private final LdapAuthenticator ldap;
    private final UserRepository users;
    private final SessionRepository sessions;
    private final AccessApi access;
    private final CryptoService crypto;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;

    public DirectoryService(DirectoryRepository repo, LdapAuthenticator ldap, UserRepository users,
            SessionRepository sessions, AccessApi access, CryptoService crypto, TransactionTemplate tx,
            ApplicationEventPublisher events) {
        this.repo = repo;
        this.ldap = ldap;
        this.users = users;
        this.sessions = sessions;
        this.access = access;
        this.crypto = crypto;
        this.tx = tx;
        this.events = events;
    }

    // ---------------------------------------------------------------- cấu hình (Admin)

    public ConnectionView get(UUID actor) {
        requireAdmin(actor);
        return repo.connection().map(DirectoryService::view).orElse(null);
    }

    /** {@code bindPassword} rỗng = giữ mật khẩu cũ; lưu mã hóa (INV-17). */
    public ConnectionView save(UUID actor, Connection c, String bindPassword) {
        requireAdmin(actor);
        if (blank(c.url()) || !(c.url().startsWith("ldap://") || c.url().startsWith("ldaps://")) || blank(c.bindDn())
                || blank(c.userBase()) || blank(c.groupBase())) {
            throw ApiException.badRequest("INVALID_DIRECTORY", "Nhập URL ldap(s)://, tài khoản dịch vụ, nhánh người dùng và nhóm");
        }
        Connection toSave = new Connection(c.enabled(), c.url().trim(), c.bindDn().trim(),
                blank(bindPassword) ? null : crypto.encrypt(bindPassword), c.userBase().trim(),
                blank(c.userFilter()) ? "(|(uid={0})(sAMAccountName={0})(mail={0}))" : c.userFilter().trim(),
                c.groupBase().trim(), blank(c.memberAttribute()) ? "member" : c.memberAttribute().trim(),
                blank(c.displayNameAttribute()) ? "cn" : c.displayNameAttribute().trim(),
                blank(c.mailAttribute()) ? "mail" : c.mailAttribute().trim(),
                blank(c.disabledGroupDn()) ? null : c.disabledGroupDn().trim(), null, null, null);
        tx.executeWithoutResult(s -> {
            repo.save(toSave, actor);
            events.publishEvent(AuditableEvent.of(actor, "CHANGE_CONFIG", "DIRECTORY", "ldap",
                    Map.of("url", toSave.url(), "enabled", toSave.enabled())));
        });
        return get(actor);
    }

    /** Kiểm tra kết nối: bind tài khoản dịch vụ, đếm người dùng. */
    public Map<String, Object> test(UUID actor) {
        requireAdmin(actor);
        LdapAuthenticator.Config cfg = config().orElseThrow(() -> ApiException.badRequest("DIRECTORY_NOT_CONFIGURED",
                "Chưa cấu hình kết nối thư mục"));
        try {
            return Map.of("ok", true, "users", ldap.test(cfg));
        } catch (LdapAuthenticator.DirectoryUnavailable e) {
            return Map.of("ok", false, "message", e.getMessage());
        }
    }

    public List<Mapping> mappings(UUID actor) {
        requireAdmin(actor);
        return repo.mappings();
    }

    public List<Mapping> saveMappings(UUID actor, List<Mapping> mappings) {
        requireAdmin(actor);
        for (Mapping m : mappings) {
            if (blank(m.groupDn()) || m.roleId() == null || m.scopeType() == null
                    || ("ORGANIZATION".equals(m.scopeType()) != (m.scopeId() == null))) {
                throw ApiException.badRequest("INVALID_MAPPING", "Ánh xạ nhóm không hợp lệ");
            }
        }
        tx.executeWithoutResult(s -> {
            repo.replaceMappings(mappings);
            events.publishEvent(AuditableEvent.of(actor, "CHANGE_PERMISSION", "DIRECTORY", "mappings",
                    Map.of("count", mappings.size())));
        });
        return repo.mappings();
    }

    // ---------------------------------------------------------------- đăng nhập

    /**
     * Xác thực tên đăng nhập thư mục ở các Tổ chức bật LDAP; thành công thì tạo/cập nhật tài khoản DIRECTORY và trả về
     * id người dùng cùng Tổ chức. Máy chủ thư mục không phản hồi → DIRECTORY_UNAVAILABLE (AC3).
     */
    public Optional<UserRepository.LoginRef> authenticate(String login, String password) {
        LdapAuthenticator.DirectoryUnavailable unavailable = null;
        for (UUID org : repo.organizations()) {
            try {
                Optional<UserRepository.LoginRef> ref = OrganizationContext.callAs(org, () -> config()
                        .flatMap(cfg -> ldap.authenticate(cfg, login, password))
                        .filter(u -> !u.disabled())
                        .map(u -> new UserRepository.LoginRef(provision(u), org)));
                if (ref.isPresent()) {
                    return ref;
                }
            } catch (LdapAuthenticator.DirectoryUnavailable e) {
                unavailable = e;
            }
        }
        if (unavailable != null) {
            throw new ApiException(503, "DIRECTORY_UNAVAILABLE", "Không kết nối được máy chủ thư mục, thử lại sau");
        }
        return Optional.empty();
    }

    /** Kiểm tra lại mật khẩu người dùng DIRECTORY đã có (gọi trong ngữ cảnh Tổ chức của người dùng). */
    public boolean verify(String externalId, String password) {
        LdapAuthenticator.Config cfg = config().orElse(null);
        if (cfg == null) {
            return false;
        }
        Optional<DirectoryUser> u;
        try {
            u = ldap.findByDn(cfg, externalId);
        } catch (LdapAuthenticator.DirectoryUnavailable e) {
            throw new ApiException(503, "DIRECTORY_UNAVAILABLE", "Không kết nối được máy chủ thư mục, thử lại sau");
        }
        if (u.isEmpty() || u.get().disabled()) {
            return false;
        }
        String login = u.get().mail() != null ? u.get().mail() : externalId;
        try {
            return ldap.authenticate(cfg, login, password).isPresent();
        } catch (LdapAuthenticator.DirectoryUnavailable e) {
            throw new ApiException(503, "DIRECTORY_UNAVAILABLE", "Không kết nối được máy chủ thư mục, thử lại sau");
        }
    }

    /** Tạo hoặc cập nhật tài khoản theo thư mục, vai trò theo ánh xạ nhóm (AC1). */
    UUID provision(DirectoryUser u) {
        String email = u.mail() != null ? u.mail() : u.dn();
        String name = u.displayName() != null ? u.displayName() : email;
        UUID id = tx.execute(s -> {
            UUID existing = repo.userByEmail(email).orElse(null);
            UUID uid;
            if (existing == null) {
                uid = repo.insertDirectoryUser(email, name, u.dn());
                events.publishEvent(AuditableEvent.of(null, "CHANGE_PERMISSION", "USER", uid.toString(),
                        Map.of("operation", "PROVISION_DIRECTORY_USER", "email", email)));
            } else {
                uid = existing;
                repo.updateDirectoryUser(uid, email, name, u.dn());
            }
            access.replaceDirectoryAssignments(uid, assignments(u.groups()));
            return uid;
        });
        return id;
    }

    private List<AssignmentSpec> assignments(List<String> groups) {
        return repo.mappings().stream().filter(m -> groups.stream().anyMatch(g -> g.equalsIgnoreCase(m.groupDn())))
                .map(m -> new AssignmentSpec(m.roleId(), ScopeType.valueOf(m.scopeType()), m.scopeId())).toList();
    }

    // ---------------------------------------------------------------- đồng bộ (mỗi giờ)

    @Scheduled(cron = "${camhub.directory.sync-cron:0 7 * * * *}")
    public void syncAll() {
        for (UUID org : repo.organizations()) {
            try {
                OrganizationContext.runAs(org, this::syncOrganization);
            } catch (RuntimeException e) {
                log.warn("Đồng bộ thư mục của Tổ chức {} lỗi: {}", org, e.toString());
            }
        }
    }

    /** AC2: tài khoản bị khóa/xóa trên thư mục → vô hiệu hóa và thu hồi phiên; còn lại cập nhật tên, email, vai trò. */
    public SyncResult syncOrganization() {
        LdapAuthenticator.Config cfg = config().orElse(null);
        if (cfg == null) {
            return new SyncResult(0, 0, 0, "Chưa cấu hình");
        }
        int checked = 0;
        int updated = 0;
        int disabled = 0;
        try {
            for (DirectoryRepository.DirectoryUserRow row : repo.directoryUsers()) {
                checked++;
                Optional<DirectoryUser> u = ldap.findByDn(cfg, row.externalId());
                if (u.isEmpty() || u.get().disabled()) {
                    if (!UserStatus.DISABLED.name().equals(row.status())) {
                        tx.executeWithoutResult(s -> {
                            users.setStatus(row.id(), UserStatus.DISABLED);
                            sessions.revokeAllOf(row.id(), "DIRECTORY_DISABLED");
                            events.publishEvent(AuditableEvent.of(null, "CHANGE_PERMISSION", "USER", row.id().toString(),
                                    Map.of("operation", "DIRECTORY_DISABLE")));
                        });
                        disabled++;
                    }
                    continue;
                }
                provision(u.get());
                if (UserStatus.DISABLED.name().equals(row.status())) {
                    tx.executeWithoutResult(s -> users.setStatus(row.id(), UserStatus.ACTIVE));
                }
                updated++;
            }
            String msg = "Kiểm tra " + checked + ", cập nhật " + updated + ", vô hiệu hóa " + disabled;
            tx.executeWithoutResult(s -> repo.syncResult("OK", msg));
            return new SyncResult(checked, updated, disabled, msg);
        } catch (LdapAuthenticator.DirectoryUnavailable e) {
            tx.executeWithoutResult(s -> repo.syncResult("ERROR", e.getMessage()));
            return new SyncResult(checked, updated, disabled, e.getMessage());
        }
    }

    public SyncResult syncNow(UUID actor) {
        requireAdmin(actor);
        return syncOrganization();
    }

    // ---------------------------------------------------------------- chung

    private Optional<LdapAuthenticator.Config> config() {
        return repo.connection().filter(Connection::enabled).map(c -> new LdapAuthenticator.Config(c.url(), c.bindDn(),
                c.bindPasswordEnc() == null ? "" : crypto.decrypt(c.bindPasswordEnc()), c.userBase(), c.userFilter(),
                c.groupBase(), c.memberAttribute(), c.displayNameAttribute(), c.mailAttribute(), c.disabledGroupDn()));
    }

    private void requireAdmin(UUID actor) {
        if (!access.hasOrganizationWide(actor, Permission.MANAGE_USERS)) {
            throw ApiException.forbidden();
        }
    }

    private static ConnectionView view(Connection c) {
        return new ConnectionView(c.enabled(), c.url(), c.bindDn(), c.bindPasswordEnc() != null, c.userBase(),
                c.userFilter(), c.groupBase(), c.memberAttribute(), c.displayNameAttribute(), c.mailAttribute(),
                c.disabledGroupDn(), c.lastSyncAt(), c.lastSyncStatus(), c.lastSyncMessage());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
