package vn.camhub.identity.application;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/** Argon2id (US-30 ghi chú kỹ thuật), tham số khuyến nghị của OWASP: m=19 MiB, t=2, p=1. */
@Component
public class PasswordHasher {

    private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2);
    /** Hash giả để so sánh khi email không tồn tại, tránh lộ email qua thời gian phản hồi. */
    private final String dummy = encoder.encode("camhub-dummy-password");

    public String hash(String raw) {
        return encoder.encode(raw);
    }

    public boolean matches(String raw, String hash) {
        return hash != null && encoder.matches(raw, hash);
    }

    public void burnTime(String raw) {
        encoder.matches(raw, dummy);
    }

    /** Chính sách mật khẩu tối thiểu cho tài khoản cục bộ. */
    public static void validateStrength(String raw) {
        if (raw == null || raw.length() < 10 || raw.chars().noneMatch(Character::isDigit)
                || raw.chars().noneMatch(Character::isLetter)) {
            throw vn.camhub.platform.security.ApiException.badRequest("WEAK_PASSWORD",
                    "Mật khẩu phải dài tối thiểu 10 ký tự, có cả chữ và số");
        }
    }
}
