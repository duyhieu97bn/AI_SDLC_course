package vn.camhub.identity.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.camhub.platform.tenancy.OrganizationContext;

@Repository
public class SessionRepository {

    public record SessionRef(UUID sessionId, UUID organizationId, boolean reused) {
    }

    public record SessionRow(UUID id, UUID userId, Instant lastActiveAt, Instant revokedAt) {
    }

    private final JdbcClient jdbc;

    public SessionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Tra refresh token trước khi biết Tổ chức (hàm SECURITY DEFINER, V1_1). */
    public Optional<SessionRef> lookup(String tokenHash) {
        return jdbc.sql("select session_id, organization_id, reused from camhub_session_lookup(?)").param(tokenHash)
                .query((rs, i) -> new SessionRef(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getBoolean(3)))
                .optional();
    }

    public void insert(UUID id, UUID userId, String tokenHash, String ip, String userAgent, Instant now) {
        jdbc.sql("""
                insert into user_session (id, organization_id, user_id, refresh_token_hash, ip_address, user_agent,
                                          started_at, last_active_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """).params(id, OrganizationContext.require(), userId, tokenHash, ip, truncate(userAgent),
                        Timestamp.from(now), Timestamp.from(now)).update();
    }

    public Optional<SessionRow> findForUpdate(UUID id) {
        return jdbc.sql("select id, user_id, last_active_at, revoked_at from user_session where id = ? for update")
                .param(id).query((rs, i) -> new SessionRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getTimestamp(3).toInstant(),
                        rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant()))
                .optional();
    }

    public void rotate(UUID id, String newHash, Instant now) {
        jdbc.sql("""
                update user_session set previous_token_hash = refresh_token_hash, refresh_token_hash = ?,
                       last_active_at = ? where id = ?
                """).params(newHash, Timestamp.from(now), id).update();
    }

    public void revoke(UUID id, String reason) {
        jdbc.sql("update user_session set revoked_at = now(), revoke_reason = ? where id = ? and revoked_at is null")
                .params(reason, id).update();
    }

    public int revokeAllOf(UUID userId, String reason) {
        return jdbc.sql("update user_session set revoked_at = now(), revoke_reason = ? where user_id = ? and revoked_at is null")
                .params(reason, userId).update();
    }

    /** Phiên còn hiệu lực và người dùng còn ACTIVE (kiểm tra mỗi yêu cầu API). */
    public boolean isActive(UUID sessionId, UUID userId) {
        return jdbc.sql("""
                select exists (select 1 from user_session s join app_user u on u.id = s.user_id
                               where s.id = ? and s.user_id = ? and s.revoked_at is null and u.status = 'ACTIVE')
                """).params(sessionId, userId).query(Boolean.class).single();
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 512 ? s : s.substring(0, 512);
    }
}
