package vn.camhub.platform.security;

import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Người dùng đã đăng nhập của yêu cầu hiện tại, lấy từ access token. */
public record CurrentUser(UUID userId, UUID organizationId, UUID sessionId) {

    public static Optional<CurrentUser> current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CurrentUser u) {
            return Optional.of(u);
        }
        return Optional.empty();
    }

    public static CurrentUser require() {
        return current().orElseThrow(() -> new ApiException(401, "UNAUTHENTICATED", "Chưa đăng nhập"));
    }
}
