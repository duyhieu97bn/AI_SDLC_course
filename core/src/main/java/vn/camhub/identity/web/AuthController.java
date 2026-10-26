package vn.camhub.identity.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.camhub.identity.application.AuthService;
import vn.camhub.identity.application.AuthService.LoginResult;
import vn.camhub.identity.application.AuthService.Tokens;
import vn.camhub.identity.application.AuthService.TwoFactorPending;
import vn.camhub.identity.application.TwoFactorService;
import vn.camhub.identity.application.AuthService.UserSummary;
import vn.camhub.platform.audit.AuditableEvent;

/** POST /auth/login, /auth/refresh, /auth/logout (SADD 3.7.1). Refresh token chỉ nằm trong cookie HttpOnly. */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    static final String COOKIE = "camhub_refresh";
    static final String COOKIE_PATH = "/api/v1/auth";

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    public record TokenResponse(String accessToken, Instant expiresAt, UserSummary user, String landingPage,
            List<String> recoveryCodes) {
    }

    public record TwoFactorResponse(boolean twoFactorRequired, String challengeId, boolean setupRequired) {
    }

    public record ChallengeRequest(@NotBlank String challengeId) {
    }

    public record VerifyRequest(@NotBlank String challengeId, @NotBlank String code) {
    }

    private final AuthService auth;
    private final TwoFactorService twoFactor;

    public AuthController(AuthService auth, TwoFactorService twoFactor) {
        this.auth = auth;
        this.twoFactor = twoFactor;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        LoginResult r = auth.login(req.email(), req.password(), AuditableEvent.currentIp(), http.getHeader("User-Agent"));
        return switch (r) {
            case Tokens t -> withCookie(t);
            case TwoFactorPending p -> ResponseEntity.ok(new TwoFactorResponse(true, p.challengeId(), p.setupRequired()));
        };
    }

    /** Thiết lập 2FA ngay trong lúc đăng nhập (vai trò bắt buộc): trả secret và URI otpauth để hiện mã QR. */
    @PostMapping("/2fa/setup")
    public Map<String, String> setup(@Valid @RequestBody ChallengeRequest req) {
        return twoFactor.beginSetup(req.challengeId());
    }

    @PostMapping("/2fa/verify")
    public ResponseEntity<TokenResponse> verify(@Valid @RequestBody VerifyRequest req) {
        return withCookie(auth.verifyTwoFactor(req.challengeId(), req.code()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@CookieValue(name = COOKIE, required = false) String token) {
        try {
            return withCookie(auth.refresh(token));
        } catch (RuntimeException e) {
            // Xóa cookie hỏng/hết hạn để trình duyệt không gửi lại mãi
            throw new ClearCookieException(e);
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = COOKIE, required = false) String token) {
        auth.logout(token);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString()).build();
    }

    private ResponseEntity<TokenResponse> withCookie(Tokens t) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie(t.refreshToken(), auth.idleTimeout()).toString())
                .body(new TokenResponse(t.accessToken(), t.accessTokenExpiresAt(), t.user(), t.landingPage(),
                        t.recoveryCodes()));
    }

    static ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(COOKIE, value).httpOnly(true).secure(true).sameSite("Strict").path(COOKIE_PATH)
                .maxAge(maxAge).build();
    }

    /** Bọc lỗi làm mới phiên để handler vừa trả lỗi vừa xóa cookie. */
    static final class ClearCookieException extends RuntimeException {
        ClearCookieException(RuntimeException cause) {
            super(cause);
        }
    }
}
