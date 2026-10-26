package vn.camhub.notification.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.camhub.platform.tenancy.OrganizationContext;

/** Bảng notification (V5_2). */
@Repository
public class NotificationRepository {

    public record Notification(UUID id, UUID userId, UUID alertId, String channel, String title, String body,
            String link, String status, int attempts, Instant createdAt, Instant readAt, String attachmentBucket,
            String attachmentKey) {
    }

    private static final String COLUMNS = "id, user_id, alert_id, channel, title, body, link, status, attempts,"
            + " created_at, read_at, attachment_bucket, attachment_key";

    private final JdbcClient jdbc;

    public NotificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID insert(UUID userId, UUID alertId, String channel, String title, String body, String link,
            String status) {
        return insert(userId, alertId, channel, title, body, link, status, Instant.now());
    }

    /** {@code notBefore}: chưa gửi trước thời điểm này (đợi ảnh sự kiện, US-36). */
    public UUID insert(UUID userId, UUID alertId, String channel, String title, String body, String link,
            String status, Instant notBefore) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into notification (id, organization_id, user_id, alert_id, channel, title, body, link, status,
                                          sent_at, next_attempt_at)
                values (?, ?, ?, ?, ?, left(?, 300), left(?, 2000), ?, ?, case when ? = 'SENT' then now() end, ?)
                """).params(id, OrganizationContext.require(), userId, alertId, channel, title, body, link, status,
                status, Timestamp.from(notBefore)).update();
        return id;
    }

    /** Ảnh sự kiện đã sẵn sàng: gắn vào các thông báo đang chờ của cảnh báo và cho gửi ngay. */
    public int attach(UUID alertId, String bucket, String key) {
        return jdbc.sql("""
                update notification set attachment_bucket = ?, attachment_key = ?,
                    next_attempt_at = least(next_attempt_at, now())
                where alert_id = ? and status = 'PENDING'
                """).params(bucket, key, alertId).update();
    }

    /** Ảnh lỗi: gửi ngay không kèm ảnh. */
    public int release(UUID alertId) {
        return jdbc.sql("update notification set next_attempt_at = least(next_attempt_at, now())"
                + " where alert_id = ? and status = 'PENDING'").param(alertId).update();
    }

    public List<Notification> web(UUID userId, boolean unreadOnly, int limit) {
        return jdbc.sql("select " + COLUMNS + " from notification where user_id = ? and channel = 'WEB'"
                + (unreadOnly ? " and read_at is null" : "") + " order by created_at desc limit ?")
                .params(userId, limit).query(NotificationRepository::map).list();
    }

    public long unread(UUID userId) {
        return jdbc.sql("select count(*) from notification where user_id = ? and channel = 'WEB' and read_at is null")
                .param(userId).query(Long.class).single();
    }

    public void markRead(UUID userId, UUID id) {
        jdbc.sql("update notification set read_at = now() where id = ? and user_id = ? and read_at is null")
                .params(id, userId).update();
    }

    public void markAllRead(UUID userId) {
        jdbc.sql("update notification set read_at = now() where user_id = ? and channel = 'WEB' and read_at is null")
                .param(userId).update();
    }

    public List<UUID> organizationsWithPending() {
        return jdbc.sql("select camhub_pending_notification_orgs()").query(UUID.class).list();
    }

    /** Lấy và khóa thông báo chờ gửi qua kênh ngoài (email, Telegram, Zalo); nhiều bản Core chạy song song an toàn. */
    public List<Notification> lockPending(int limit) {
        return jdbc.sql("select " + COLUMNS + " from notification where status = 'PENDING' and channel <> 'WEB'"
                + " and next_attempt_at <= now() order by next_attempt_at limit ? for update skip locked")
                .param(limit).query(NotificationRepository::map).list();
    }

    public void markSent(UUID id) {
        jdbc.sql("update notification set status = 'SENT', sent_at = now(), attempts = attempts + 1 where id = ?")
                .param(id).update();
    }

    /** Ghi lỗi; quá {@code maxAttempts} thì FAILED, chưa thì hẹn lần sau. */
    public void markAttemptFailed(UUID id, String error, int maxAttempts, Instant next) {
        jdbc.sql("""
                update notification set attempts = attempts + 1, last_error = left(?, 500), next_attempt_at = ?,
                    status = case when attempts + 1 >= ? then 'FAILED' else 'PENDING' end
                where id = ?
                """).params(error, Timestamp.from(next), maxAttempts, id).update();
    }

    private static Notification map(ResultSet rs, int i) throws SQLException {
        Timestamp read = rs.getTimestamp("read_at");
        return new Notification(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getObject("alert_id", UUID.class), rs.getString("channel"), rs.getString("title"),
                rs.getString("body"), rs.getString("link"), rs.getString("status"), rs.getInt("attempts"),
                rs.getTimestamp("created_at").toInstant(), read == null ? null : read.toInstant(),
                rs.getString("attachment_bucket"), rs.getString("attachment_key"));
    }
}
