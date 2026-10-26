package vn.camhub.identity.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.camhub.identity.domain.User;
import vn.camhub.identity.domain.User.AuthSource;
import vn.camhub.identity.domain.User.UserStatus;
import vn.camhub.platform.tenancy.OrganizationContext;

@Repository
public class UserRepository {

    public record LoginRef(UUID userId, UUID organizationId) {
    }

    private static final String COLUMNS = """
            id, organization_id, email, display_name, auth_source, password_hash, status, language,
            failed_login_count, two_factor_enabled, created_by, created_at, last_login_at
            """;

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Tra email trước khi biết Tổ chức, qua hàm SECURITY DEFINER (V1_1). */
    public Optional<LoginRef> lookupForLogin(String email) {
        return jdbc.sql("select user_id, organization_id from camhub_login_lookup(?)").param(email)
                .query((rs, i) -> new LoginRef(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)))
                .optional();
    }

    public Optional<String> externalId(UUID id) {
        return jdbc.sql("select external_id from app_user where id = ?").param(id).query(String.class).optional();
    }

    public Optional<User> find(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from app_user where id = ?").param(id).query(UserRepository::map)
                .optional();
    }

    public Optional<User> findForUpdate(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from app_user where id = ? for update").param(id)
                .query(UserRepository::map).optional();
    }

    public List<User> list(boolean all, Set<UUID> ids, UUID createdBy, String q) {
        String like = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase() + "%";
        return jdbc.sql("select " + COLUMNS + """
                 from app_user
                where (? or id = any(?) or created_by = ?)
                  and (cast(? as text) is null or lower(email) like ? or lower(display_name) like ?)
                order by display_name
                """).params(all, ids.toArray(UUID[]::new), createdBy, like, like, like)
                .query(UserRepository::map).list();
    }

    public UUID insertLocal(String email, String displayName, String passwordHash, String language, UUID createdBy) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into app_user (id, organization_id, email, display_name, auth_source, password_hash, language, created_by)
                values (?, ?, ?, ?, 'LOCAL', ?, ?, ?)
                """).params(id, OrganizationContext.require(), email.trim().toLowerCase(), displayName, passwordHash,
                        language, createdBy).update();
        return id;
    }

    public void recordFailure(UUID id, int failedCount, boolean lock) {
        jdbc.sql("""
                update app_user set failed_login_count = ?, status = case when ? then 'LOCKED' else status end,
                       locked_at = case when ? then now() else locked_at end, updated_at = now()
                where id = ?
                """).params(failedCount, lock, lock, id).update();
    }

    public void recordSuccess(UUID id, Instant at) {
        jdbc.sql("update app_user set failed_login_count = 0, last_login_at = ?, updated_at = now() where id = ?")
                .params(Timestamp.from(at), id).update();
    }

    public void updateProfile(UUID id, String displayName, String language) {
        jdbc.sql("""
                update app_user set display_name = coalesce(?, display_name), language = coalesce(?, language),
                       updated_at = now() where id = ?
                """).params(displayName, language, id).update();
    }

    public void setStatus(UUID id, UserStatus status) {
        jdbc.sql("""
                update app_user set status = ?, failed_login_count = case when ? = 'ACTIVE' then 0 else failed_login_count end,
                       locked_at = case when ? = 'ACTIVE' then null else locked_at end, updated_at = now()
                where id = ?
                """).params(status.name(), status.name(), status.name(), id).update();
    }

    public void setPassword(UUID id, String hash) {
        jdbc.sql("update app_user set password_hash = ?, updated_at = now() where id = ?").params(hash, id).update();
    }

    static User map(ResultSet rs, int i) throws SQLException {
        Timestamp last = rs.getTimestamp("last_login_at");
        return new User(rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class),
                rs.getString("email"), rs.getString("display_name"), AuthSource.valueOf(rs.getString("auth_source")),
                rs.getString("password_hash"), UserStatus.valueOf(rs.getString("status")), rs.getString("language"),
                rs.getInt("failed_login_count"), rs.getBoolean("two_factor_enabled"),
                rs.getObject("created_by", UUID.class), rs.getTimestamp("created_at").toInstant(),
                last == null ? null : last.toInstant());
    }
}
