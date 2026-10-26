package vn.camhub.platform.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Đọc sự kiện chưa gửi trong outbox và đẩy sang broker.
 *
 * <ul>
 *   <li>Nhiều bản Core chạy song song an toàn nhờ {@code FOR UPDATE SKIP LOCKED}.</li>
 *   <li>Gửi lỗi thì tăng {@code attempts} và lùi lần thử sau theo cấp số nhân (tối đa 5 phút);
 *       sự kiện không bao giờ bị bỏ, metric {@code camhub_outbox_pending} để cảnh báo tồn đọng.</li>
 *   <li>Đảm bảo gửi ít nhất một lần; thứ tự không đảm bảo, consumer phải idempotent theo messageId.</li>
 * </ul>
 */
@Component
public final class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    static final int BATCH_SIZE = 100;
    static final Duration MAX_BACKOFF = Duration.ofMinutes(5);
    static final Duration RETENTION = Duration.ofDays(7);

    private static final RowMapper<OutboxEvent> MAPPER = (rs, i) -> new OutboxEvent(
            rs.getObject("id", UUID.class),
            rs.getObject("organization_id", UUID.class),
            rs.getString("aggregate_type"),
            rs.getString("aggregate_id"),
            rs.getString("event_type"),
            rs.getString("exchange"),
            rs.getString("routing_key"),
            rs.getString("payload"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getInt("attempts"));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final OutboxTransport transport;
    private final Clock clock;
    private final Counter published;
    private final Counter failed;

    public OutboxPublisher(JdbcTemplate jdbc, TransactionTemplate tx, OutboxTransport transport, Clock clock,
            MeterRegistry meters) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.transport = transport;
        this.clock = clock;
        this.published = meters.counter("camhub.outbox.published");
        this.failed = meters.counter("camhub.outbox.failed");
        Gauge.builder("camhub.outbox.pending", this, OutboxPublisher::pendingCount).register(meters);
    }

    /** @return số sự kiện đã gửi thành công trong lượt này */
    @Scheduled(fixedDelayString = "${camhub.outbox.poll-interval:1s}",
            initialDelayString = "${camhub.outbox.initial-delay:5s}")
    public int publishPending() {
        Integer sent = tx.execute(status -> {
            List<OutboxEvent> batch = jdbc.query("""
                    select * from outbox_event
                    where published_at is null and next_attempt_at <= ?
                    order by created_at
                    limit ?
                    for update skip locked
                    """, MAPPER, Timestamp.from(clock.instant()), BATCH_SIZE);
            int ok = 0;
            for (OutboxEvent event : batch) {
                if (trySend(event)) {
                    ok++;
                }
            }
            return ok;
        });
        return sent == null ? 0 : sent;
    }

    private boolean trySend(OutboxEvent event) {
        Instant now = clock.instant();
        try {
            transport.send(event);
            jdbc.update("update outbox_event set published_at = ?, attempts = attempts + 1, last_error = null where id = ?",
                    Timestamp.from(now), event.id());
            published.increment();
            return true;
        } catch (RuntimeException e) {
            int attempts = event.attempts() + 1;
            Instant next = now.plus(backoff(attempts));
            jdbc.update("update outbox_event set attempts = ?, next_attempt_at = ?, last_error = ? where id = ?",
                    attempts, Timestamp.from(next), truncate(e.toString()), event.id());
            failed.increment();
            log.warn("Gửi sự kiện outbox {} ({}) thất bại lần {}, thử lại lúc {}", event.id(), event.eventType(),
                    attempts, next, e);
            return false;
        }
    }

    static Duration backoff(int attempts) {
        long seconds = 1L << Math.min(attempts, 16);
        return seconds >= MAX_BACKOFF.toSeconds() ? MAX_BACKOFF : Duration.ofSeconds(seconds);
    }

    /** Xóa sự kiện đã gửi quá 7 ngày để bảng không phình. */
    @Scheduled(cron = "${camhub.outbox.cleanup-cron:0 30 3 * * *}")
    public int purgePublished() {
        return jdbc.update("delete from outbox_event where published_at < ?",
                Timestamp.from(clock.instant().minus(RETENTION)));
    }

    private double pendingCount() {
        try {
            Long n = jdbc.queryForObject("select count(*) from outbox_event where published_at is null", Long.class);
            return n == null ? 0 : n;
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    private static String truncate(String s) {
        return s.length() <= 1000 ? s : s.substring(0, 1000);
    }
}
