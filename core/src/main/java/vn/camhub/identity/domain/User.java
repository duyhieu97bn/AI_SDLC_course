package vn.camhub.identity.domain;

import java.time.Instant;
import java.util.UUID;

/** Tài khoản người dùng (class diagram mục 6). */
public record User(
        UUID id,
        UUID organizationId,
        String email,
        String displayName,
        AuthSource authSource,
        String passwordHash,
        UserStatus status,
        String language,
        int failedLoginCount,
        boolean twoFactorEnabled,
        UUID createdBy,
        Instant createdAt,
        Instant lastLoginAt) {

    public enum AuthSource { LOCAL, DIRECTORY }

    public enum UserStatus { ACTIVE, LOCKED, DISABLED }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }
}
