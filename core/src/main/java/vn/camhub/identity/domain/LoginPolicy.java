package vn.camhub.identity.domain;

import java.time.Duration;
import java.time.Instant;

/** Quy tắc đăng nhập: RULE-04 / INV-12 (khóa sau 5 lần sai liên tiếp), hết phiên sau 12 giờ không hoạt động. */
public record LoginPolicy(int maxFailedAttempts, Duration idleTimeout) {

    public static final LoginPolicy DEFAULT = new LoginPolicy(5, Duration.ofHours(12));

    /** Số lần sai sau lần sai này đạt ngưỡng thì khóa. */
    public boolean shouldLock(int failedCountAfterThisAttempt) {
        return failedCountAfterThisAttempt >= maxFailedAttempts;
    }

    public boolean isIdleExpired(Instant lastActiveAt, Instant now) {
        return lastActiveAt.plus(idleTimeout).isBefore(now);
    }
}
