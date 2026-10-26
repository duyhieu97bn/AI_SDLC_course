package vn.camhub.identity.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.camhub.access.api.AccessApi;
import vn.camhub.identity.domain.Totp;
import vn.camhub.identity.domain.User;
import vn.camhub.identity.infrastructure.TwoFactorRepository;
import vn.camhub.identity.infrastructure.UserRepository;
import vn.camhub.platform.audit.AuditableEvent;
import vn.camhub.platform.crypto.CryptoService;
import vn.camhub.platform.security.ApiException;

/**
 * Xác thực hai lớp (US-31, UC-02, INV-11, INV-25). TOTP secret mã hóa AES-256-GCM theo Tổ chức (INV-17);
 * 10 mã dự phòng chỉ lưu SHA-256, mỗi mã dùng một lần.
 *
 * <p>Thử thách đăng nhập ({@code challenge}) lưu ở Redis 5 phút, tối đa 5 lần nhập sai.
 */
@Service
public class TwoFactorService {

    public record Challenge(UUID userId, UUID organizationId, boolean setup, String pendingSecret, String ip,
            String userAgent, int attempts) {
    }

    static final Duration CHALLENGE_TTL = Duration.ofMinutes(5);
    static final int MAX_ATTEMPTS = 5;
    static final int RECOVERY_CODES = 10;
    private static final String ISSUER = "CamHub";
    private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final TwoFactorRepository repo;
    private final UserRepository users;
    private final CryptoService crypto;
    private final AccessApi access;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public TwoFactorService(StringRedisTemplate redis, ObjectMapper json, TwoFactorRepository repo,
            UserRepository users, CryptoService crypto, AccessApi access, ApplicationEventPublisher events,
            Clock clock) {
        this.redis = redis;
        this.json = json;
        this.repo = repo;
        this.users = users;
        this.crypto = crypto;
        this.access = access;
        this.events = events;
        this.clock = clock;
    }

    /** INV-11: một vai trò của người dùng yêu cầu 2FA. */
    public boolean isRequired(UUID userId) {
        return access.rolesOf(userId).stream().anyMatch(AccessApi.RoleRef::requireTwoFactor);
    }

    // ---------------------------------------------------------------- thử thách khi đăng nhập

    public String createChallenge(User u, boolean setup, String ip, String userAgent) {
        String id = UUID.randomUUID().toString();
        save(id, new Challenge(u.id(), u.organizationId(), setup, null, ip, userAgent, 0));
        return id;
    }

    public Optional<Challenge> challenge(String id) {
        try {
            String v = redis.opsForValue().get(key(id));
            return v == null ? Optional.empty() : Optional.of(json.readValue(v, Challenge.class));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    /** Bước thiết lập trong lúc đăng nhập: sinh secret mới, chỉ lưu vào DB khi người dùng nhập đúng mã đầu tiên. */
    public Map<String, String> beginSetup(String challengeId) {
        Challenge c = challenge(challengeId).filter(Challenge::setup).orElseThrow(TwoFactorService::expired);
        String secret = c.pendingSecret() != null ? c.pendingSecret() : Totp.newSecret();
        save(challengeId, new Challenge(c.userId(), c.organizationId(), true, secret, c.ip(), c.userAgent(),
                c.attempts()));
        String email = users.find(c.userId()).map(User::email).orElse("user");
        return Map.of("secret", secret, "otpauthUri", Totp.otpauthUri(ISSUER, email, secret));
    }

    public Challenge recordFailedAttempt(String challengeId, Challenge c) {
        Challenge next = new Challenge(c.userId(), c.organizationId(), c.setup(), c.pendingSecret(), c.ip(),
                c.userAgent(), c.attempts() + 1);
        if (next.attempts() >= MAX_ATTEMPTS) {
            redis.delete(key(challengeId));
        } else {
            save(challengeId, next);
        }
        return next;
    }

    public void consume(String challengeId) {
        redis.delete(key(challengeId));
    }

    /** Kiểm tra mã TOTP hoặc mã dự phòng của người dùng đã bật 2FA (gọi trong ngữ cảnh Tổ chức). */
    public boolean verifyExisting(UUID userId, String code) {
        String normalized = code == null ? "" : code.replace(" ", "").replace("-", "").toUpperCase();
        if (normalized.matches("\\d{6}")) {
            return repo.secretOf(userId).map(crypto::decrypt)
                    .map(secret -> Totp.verify(secret, normalized, clock.instant())).orElse(false);
        }
        if (normalized.length() == 8 && repo.useRecoveryCode(userId, AuthService.sha256(normalized))) {
            events.publishEvent(AuditableEvent.of(userId, "LOGIN", "USER", userId.toString(),
                    Map.of("method", "RECOVERY_CODE", "remaining", repo.remainingRecoveryCodes(userId))));
            return true;
        }
        return false;
    }

    /**
     * Bật 2FA với secret đã xác nhận bằng một mã đúng; trả về 10 mã dự phòng (chỉ hiện một lần).
     * Không đánh dấu @Transactional: chạy trong giao dịch của bên gọi; mã sai thì ném lỗi trước khi ghi gì,
     * nên không làm giao dịch bên gọi bị đánh dấu rollback (bên gọi còn phải ghi số lần nhập sai).
     */
    public List<String> enable(UUID userId, String secret, String code) {
        if (!Totp.verify(secret, code == null ? "" : code.trim(), clock.instant())) {
            throw invalidCode();
        }
        repo.enable(userId, crypto.encrypt(secret));
        List<String> codes = newRecoveryCodes(userId);
        events.publishEvent(AuditableEvent.of(userId, "CHANGE_CONFIG", "USER", userId.toString(),
                Map.of("operation", "ENABLE_2FA")));
        return codes;
    }

    // ---------------------------------------------------------------- tự quản lý trong hồ sơ

    public Map<String, Object> status(UUID userId) {
        User u = users.find(userId).orElseThrow(() -> ApiException.notFound("người dùng"));
        return Map.of("enabled", u.twoFactorEnabled(), "required", isRequired(userId),
                "remainingRecoveryCodes", u.twoFactorEnabled() ? repo.remainingRecoveryCodes(userId) : 0);
    }

    public Map<String, String> beginSelfSetup(UUID userId) {
        String secret = Totp.newSecret();
        redis.opsForValue().set("2fa:pending:" + userId, secret, Duration.ofMinutes(10));
        String email = users.find(userId).map(User::email).orElse("user");
        return Map.of("secret", secret, "otpauthUri", Totp.otpauthUri(ISSUER, email, secret));
    }

    @Transactional
    public List<String> confirmSelfSetup(UUID userId, String code) {
        String secret = redis.opsForValue().get("2fa:pending:" + userId);
        if (secret == null) {
            throw expired();
        }
        List<String> codes = enable(userId, secret, code);
        redis.delete("2fa:pending:" + userId);
        return codes;
    }

    @Transactional
    public List<String> regenerateRecoveryCodes(UUID userId, String code) {
        if (!verifyExisting(userId, code)) {
            throw invalidCode();
        }
        return newRecoveryCodes(userId);
    }

    @Transactional
    public void disable(UUID userId, String code) {
        if (isRequired(userId)) {
            throw new ApiException(409, "TWO_FACTOR_REQUIRED", "Vai trò của bạn bắt buộc xác thực hai lớp");
        }
        if (!verifyExisting(userId, code)) {
            throw invalidCode();
        }
        repo.disable(userId);
        events.publishEvent(AuditableEvent.of(userId, "CHANGE_CONFIG", "USER", userId.toString(),
                Map.of("operation", "DISABLE_2FA")));
    }

    /** Admin tắt 2FA của người dùng mất điện thoại và hết mã dự phòng; người dùng phải thiết lập lại lần đăng nhập sau. */
    @Transactional
    public void reset(UUID actorId, UUID userId) {
        repo.disable(userId);
        events.publishEvent(AuditableEvent.of(actorId, "CHANGE_PERMISSION", "USER", userId.toString(),
                Map.of("operation", "RESET_2FA")));
    }

    private List<String> newRecoveryCodes(UUID userId) {
        List<String> codes = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODES; i++) {
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < 8; k++) {
                sb.append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]);
            }
            String raw = sb.toString();
            hashes.add(AuthService.sha256(raw));
            codes.add(raw.substring(0, 4) + "-" + raw.substring(4));
        }
        repo.replaceRecoveryCodes(userId, hashes);
        return codes;
    }

    private void save(String id, Challenge c) {
        try {
            redis.opsForValue().set(key(id), json.writeValueAsString(c), CHALLENGE_TTL);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String key(String id) {
        return "2fa:challenge:" + id;
    }

    static ApiException invalidCode() {
        return new ApiException(401, "INVALID_2FA_CODE", "Mã xác thực không đúng");
    }

    static ApiException expired() {
        return new ApiException(401, "TWO_FACTOR_EXPIRED", "Phiên xác thực hai lớp đã hết hạn, vui lòng đăng nhập lại");
    }
}
