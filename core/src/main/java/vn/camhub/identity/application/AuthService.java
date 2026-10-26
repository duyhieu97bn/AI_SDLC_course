package vn.camhub.identity.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import vn.camhub.access.api.AccessApi;
import vn.camhub.identity.domain.LoginPolicy;
import vn.camhub.identity.domain.User;
import vn.camhub.identity.domain.User.AuthSource;
import vn.camhub.identity.domain.User.UserStatus;
import vn.camhub.identity.infrastructure.SessionRepository;
import vn.camhub.identity.infrastructure.UserRepository;
import vn.camhub.platform.audit.AuditableEvent;
import vn.camhub.platform.security.ApiException;
import vn.camhub.platform.security.CurrentUser;
import vn.camhub.platform.security.JwtService;
import vn.camhub.platform.security.SessionValidator;
import vn.camhub.platform.tenancy.OrganizationContext;

/**
 * Đăng nhập, làm mới phiên, đăng xuất (US-30, UC-01, SADD 3.9.1).
 *
 * <ul>
 *   <li>Refresh token ngẫu nhiên 256 bit, DB chỉ lưu SHA-256; mỗi lần làm mới thì xoay vòng token.</li>
 *   <li>Dùng lại token đã xoay vòng = token bị đánh cắp → thu hồi cả phiên.</li>
 *   <li>Phiên hết hạn sau 12 giờ không làm mới (không hoạt động) – US-30 AC3.</li>
 * </ul>
 */
@Service
public class AuthService implements SessionValidator {

    @ConfigurationProperties("camhub.security")
    public record SecurityProps(Session session, Login login) {
        public record Session(Duration idleTimeout) {
        }

        public record Login(int maxFailedAttempts) {
        }

        LoginPolicy policy() {
            return new LoginPolicy(login == null ? 5 : login.maxFailedAttempts(),
                    session == null || session.idleTimeout() == null ? Duration.ofHours(12) : session.idleTimeout());
        }
    }

    public record UserSummary(UUID id, String email, String displayName, String language, List<String> roles) {
    }

    /** Kết quả bước mật khẩu: đăng nhập xong, hoặc còn bước 2FA. */
    public sealed interface LoginResult permits Tokens, TwoFactorPending {
    }

    /**
     * Kết quả đăng nhập/làm mới; {@code refreshToken} chỉ đặt vào cookie, không trả trong body.
     * {@code recoveryCodes} chỉ có khi vừa bật 2FA trong lúc đăng nhập (hiện một lần duy nhất).
     */
    public record Tokens(String accessToken, Instant accessTokenExpiresAt, String refreshToken, UserSummary user,
            String landingPage, List<String> recoveryCodes) implements LoginResult {
    }

    /** Còn bước 2FA; {@code setupRequired}: vai trò bắt buộc 2FA mà người dùng chưa thiết lập (US-31 AC1). */
    public record TwoFactorPending(String challengeId, boolean setupRequired) implements LoginResult {
    }

    private final UserRepository users;
    private final SessionRepository sessions;
    private final PasswordHasher hasher;
    private final JwtService jwt;
    private final AccessApi access;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final LoginPolicy policy;
    private final TwoFactorService twoFactor;
    private final DirectoryService directory;
    private final SecureRandom random = new SecureRandom();

    public AuthService(UserRepository users, SessionRepository sessions, PasswordHasher hasher, JwtService jwt,
            AccessApi access, TransactionTemplate tx, ApplicationEventPublisher events, Clock clock,
            SecurityProps props, TwoFactorService twoFactor, DirectoryService directory) {
        this.twoFactor = twoFactor;
        this.directory = directory;
        this.users = users;
        this.sessions = sessions;
        this.hasher = hasher;
        this.jwt = jwt;
        this.access = access;
        this.tx = tx;
        this.events = events;
        this.clock = clock;
        this.policy = props.policy();
    }

    public Duration idleTimeout() {
        return policy.idleTimeout();
    }

    public LoginResult login(String email, String password, String ip, String userAgent) {
        String login = email == null ? "" : email.trim();
        Optional<UserRepository.LoginRef> found = users.lookupForLogin(login);
        boolean viaDirectory = false;
        if (found.isEmpty()) {
            // US-44: chưa có tài khoản cục bộ → thử tên đăng nhập thư mục (tạo tài khoản lần đầu theo nhóm AD)
            found = directory.authenticate(login, password);
            if (found.isEmpty()) {
                hasher.burnTime(password == null ? "" : password);
                throw invalidCredentials();
            }
            viaDirectory = true;
        }
        UserRepository.LoginRef ref = found.get();
        boolean preVerified = viaDirectory;
        UUID orgId = ref.organizationId();
        // Giao dịch commit cả khi đăng nhập sai, để bộ đếm và nhật ký LOGIN_FAILED được lưu; lỗi ném sau commit
        Outcome outcome = OrganizationContext.callAs(orgId, () -> tx.execute(s -> attempt(ref.userId(), password,
                ip, userAgent, preVerified)));
        return switch (outcome) {
            case Outcome.Success ok -> ok.tokens();
            case Outcome.Pending p -> p.pending();
            case Outcome.Failure f -> throw f.error();
        };
    }

    private sealed interface Outcome {
        record Success(Tokens tokens) implements Outcome {
        }

        record Pending(TwoFactorPending pending) implements Outcome {
        }

        record Failure(ApiException error) implements Outcome {
        }
    }

    private Outcome attempt(UUID userId, String password, String ip, String userAgent, boolean preVerified) {
        User u = users.findForUpdate(userId).orElseThrow(AuthService::invalidCredentials);
        if (u.status() == UserStatus.DISABLED) {
            audit(AuditableEvent.failure(u.id(), "LOGIN_FAILED", "USER", u.id().toString(), Map.of("reason", "DISABLED")));
            return new Outcome.Failure(new ApiException(403, "ACCOUNT_DISABLED", "Tài khoản đã bị vô hiệu hóa"));
        }
        if (u.status() == UserStatus.LOCKED) {
            audit(AuditableEvent.failure(u.id(), "LOGIN_FAILED", "USER", u.id().toString(), Map.of("reason", "LOCKED")));
            return new Outcome.Failure(locked());
        }
        // Tài khoản thư mục xác thực bằng LDAP (AD vô hiệu → sai mật khẩu); tài khoản cục bộ bằng Argon2id
        boolean ok = u.authSource() == AuthSource.LOCAL
                ? hasher.matches(password == null ? "" : password, u.passwordHash())
                : preVerified || users.externalId(u.id()).map(dn -> directory.verify(dn, password)).orElse(false);
        if (!ok) {
            int failed = u.failedLoginCount() + 1;
            boolean lock = policy.shouldLock(failed);
            users.recordFailure(u.id(), failed, lock);
            audit(AuditableEvent.failure(u.id(), "LOGIN_FAILED", "USER", u.id().toString(),
                    Map.of("reason", "BAD_PASSWORD", "failedCount", failed, "locked", lock)));
            return new Outcome.Failure(lock ? locked() : invalidCredentials());
        }
        // INV-11, INV-25: đã bật 2FA, hoặc vai trò bắt buộc 2FA (khi đó phải thiết lập ngay)
        if (u.twoFactorEnabled() || twoFactor.isRequired(u.id())) {
            String challenge = twoFactor.createChallenge(u, !u.twoFactorEnabled(), ip, userAgent);
            return new Outcome.Pending(new TwoFactorPending(challenge, !u.twoFactorEnabled()));
        }
        return new Outcome.Success(completeLogin(u, ip, userAgent, null, Map.of()));
    }

    private Tokens completeLogin(User u, String ip, String userAgent, List<String> recoveryCodes,
            Map<String, Object> details) {
        Instant now = clock.instant();
        users.recordSuccess(u.id(), now);
        UUID sessionId = UUID.randomUUID();
        String refresh = newRefreshToken();
        sessions.insert(sessionId, u.id(), sha256(refresh), ip, userAgent, now);
        Map<String, Object> d = new java.util.HashMap<>(details);
        d.put("sessionId", sessionId.toString());
        audit(AuditableEvent.of(u.id(), "LOGIN", "USER", u.id().toString(), d));
        Tokens t = tokensFor(u, sessionId, refresh);
        return new Tokens(t.accessToken(), t.accessTokenExpiresAt(), t.refreshToken(), t.user(), t.landingPage(),
                recoveryCodes);
    }

    /** Bước 2 (UC-02): mã TOTP/mã dự phòng; nếu đang thiết lập thì xác nhận secret mới và trả mã dự phòng. */
    public Tokens verifyTwoFactor(String challengeId, String code) {
        TwoFactorService.Challenge c = twoFactor.challenge(challengeId).orElseThrow(TwoFactorService::expired);
        Outcome outcome = OrganizationContext.callAs(c.organizationId(), () -> tx.execute(s -> {
            User u = users.findForUpdate(c.userId()).filter(User::isActive).orElse(null);
            if (u == null) {
                twoFactor.consume(challengeId);
                return new Outcome.Failure(locked());
            }
            try {
                if (c.setup()) {
                    if (c.pendingSecret() == null) {
                        return new Outcome.Failure(ApiException.badRequest("TWO_FACTOR_SETUP_FIRST",
                                "Quét mã QR để thiết lập trước"));
                    }
                    List<String> codes = twoFactor.enable(u.id(), c.pendingSecret(), code);
                    twoFactor.consume(challengeId);
                    return new Outcome.Success(completeLogin(u, c.ip(), c.userAgent(), codes,
                            Map.of("method", "TOTP_SETUP")));
                }
                if (twoFactor.verifyExisting(u.id(), code)) {
                    twoFactor.consume(challengeId);
                    return new Outcome.Success(completeLogin(u, c.ip(), c.userAgent(), null, Map.of("method", "TOTP")));
                }
            } catch (ApiException e) {
                if (!"INVALID_2FA_CODE".equals(e.code())) {
                    throw e;
                }
            }
            // Nhập sai mã 2FA cũng tính vào số lần sai liên tiếp (RULE-04)
            int failed = u.failedLoginCount() + 1;
            boolean lock = policy.shouldLock(failed);
            users.recordFailure(u.id(), failed, lock);
            TwoFactorService.Challenge after = twoFactor.recordFailedAttempt(challengeId, c);
            audit(AuditableEvent.failure(u.id(), "LOGIN_FAILED", "USER", u.id().toString(),
                    Map.of("reason", "BAD_2FA_CODE", "failedCount", failed, "locked", lock)));
            if (lock) {
                twoFactor.consume(challengeId);
                return new Outcome.Failure(locked());
            }
            return new Outcome.Failure(after.attempts() >= TwoFactorService.MAX_ATTEMPTS ? TwoFactorService.expired()
                    : TwoFactorService.invalidCode());
        }));
        return switch (outcome) {
            case Outcome.Success ok -> ok.tokens();
            case Outcome.Pending p -> throw new IllegalStateException();
            case Outcome.Failure f -> throw f.error();
        };
    }

    public Tokens refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw sessionExpired();
        }
        String hash = sha256(refreshToken);
        SessionRepository.SessionRef ref = sessions.lookup(hash).orElseThrow(AuthService::sessionExpired);
        Outcome outcome = OrganizationContext.callAs(ref.organizationId(), () -> tx.execute(s -> {
            SessionRepository.SessionRow row = sessions.findForUpdate(ref.sessionId()).orElse(null);
            if (row == null || row.revokedAt() != null) {
                return new Outcome.Failure(sessionExpired());
            }
            if (ref.reused()) {
                sessions.revoke(row.id(), "TOKEN_REUSE");
                audit(AuditableEvent.failure(row.userId(), "LOGIN_FAILED", "SESSION", row.id().toString(),
                        Map.of("reason", "REFRESH_TOKEN_REUSE")));
                return new Outcome.Failure(sessionExpired());
            }
            Instant now = clock.instant();
            if (policy.isIdleExpired(row.lastActiveAt(), now)) {
                sessions.revoke(row.id(), "IDLE_TIMEOUT");
                return new Outcome.Failure(sessionExpired());
            }
            User u = users.find(row.userId()).filter(User::isActive).orElse(null);
            if (u == null) {
                sessions.revoke(row.id(), "USER_INACTIVE");
                return new Outcome.Failure(sessionExpired());
            }
            String next = newRefreshToken();
            sessions.rotate(row.id(), sha256(next), now);
            return new Outcome.Success(tokensFor(u, row.id(), next));
        }));
        return switch (outcome) {
            case Outcome.Success ok -> ok.tokens();
            case Outcome.Pending p -> throw new IllegalStateException();
            case Outcome.Failure f -> throw f.error();
        };
    }

    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        sessions.lookup(sha256(refreshToken)).ifPresent(ref -> OrganizationContext.runAs(ref.organizationId(),
                () -> tx.executeWithoutResult(s -> sessions.revoke(ref.sessionId(), "LOGOUT"))));
    }

    /** Thu hồi mọi phiên của người dùng (vô hiệu hóa tài khoản, đổi mật khẩu). */
    public int revokeAll(UUID userId, String reason) {
        return sessions.revokeAllOf(userId, reason);
    }

    @Override
    public boolean isActive(CurrentUser user) {
        return OrganizationContext.callAs(user.organizationId(), () -> sessions.isActive(user.sessionId(), user.userId()));
    }

    private Tokens tokensFor(User u, UUID sessionId, String refresh) {
        List<AccessApi.RoleRef> roles = access.rolesOf(u.id());
        JwtService.IssuedToken token = jwt.issue(new CurrentUser(u.id(), u.organizationId(), sessionId));
        return new Tokens(token.value(), token.expiresAt(), refresh,
                new UserSummary(u.id(), u.email(), u.displayName(), u.language(),
                        roles.stream().map(AccessApi.RoleRef::name).toList()),
                LandingPage.forRoles(roles), null);
    }

    private void audit(AuditableEvent e) {
        events.publishEvent(e);
    }

    private String newRefreshToken() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static ApiException invalidCredentials() {
        return new ApiException(401, "INVALID_CREDENTIALS", "Email hoặc mật khẩu không đúng");
    }

    static ApiException locked() {
        return new ApiException(423, "ACCOUNT_LOCKED",
                "Tài khoản đã bị khóa do đăng nhập sai nhiều lần. Liên hệ quản trị viên để mở khóa");
    }

    static ApiException sessionExpired() {
        return new ApiException(401, "SESSION_EXPIRED", "Phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại");
    }
}
