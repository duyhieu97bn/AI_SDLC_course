package vn.camhub.identity.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.camhub.platform.tenancy.OrganizationContext;

/** Bảng directory_connection, directory_group_mapping (V8_2) và người dùng nguồn DIRECTORY. */
@Repository
public class DirectoryRepository {

    public record Connection(boolean enabled, String url, String bindDn, String bindPasswordEnc, String userBase,
            String userFilter, String groupBase, String memberAttribute, String displayNameAttribute,
            String mailAttribute, String disabledGroupDn, java.time.Instant lastSyncAt, String lastSyncStatus,
            String lastSyncMessage) {
    }

    public record Mapping(UUID id, String groupDn, UUID roleId, String scopeType, UUID scopeId) {
    }

    public record DirectoryUserRow(UUID id, String externalId, String status) {
    }

    private final JdbcClient jdbc;

    public DirectoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Connection> connection() {
        return jdbc.sql("""
                select enabled, url, bind_dn, bind_password_enc, user_base, user_filter, group_base, member_attribute,
                       display_name_attribute, mail_attribute, disabled_group_dn, last_sync_at, last_sync_status,
                       last_sync_message
                from directory_connection
                """).query((rs, i) -> new Connection(rs.getBoolean(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                        rs.getString(9), rs.getString(10), rs.getString(11),
                        rs.getTimestamp(12) == null ? null : rs.getTimestamp(12).toInstant(), rs.getString(13),
                        rs.getString(14)))
                .optional();
    }

    /** {@code bindPasswordEnc} null = giữ mật khẩu đã lưu. */
    public void save(Connection c, UUID by) {
        jdbc.sql("""
                insert into directory_connection (organization_id, enabled, url, bind_dn, bind_password_enc, user_base,
                    user_filter, group_base, member_attribute, display_name_attribute, mail_attribute, disabled_group_dn,
                    updated_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (organization_id) do update set enabled = excluded.enabled, url = excluded.url,
                    bind_dn = excluded.bind_dn,
                    bind_password_enc = coalesce(excluded.bind_password_enc, directory_connection.bind_password_enc),
                    user_base = excluded.user_base, user_filter = excluded.user_filter, group_base = excluded.group_base,
                    member_attribute = excluded.member_attribute,
                    display_name_attribute = excluded.display_name_attribute, mail_attribute = excluded.mail_attribute,
                    disabled_group_dn = excluded.disabled_group_dn, updated_by = excluded.updated_by,
                    updated_at = now()
                """).params(OrganizationContext.require(), c.enabled(), c.url(), c.bindDn(), c.bindPasswordEnc(),
                c.userBase(), c.userFilter(), c.groupBase(), c.memberAttribute(), c.displayNameAttribute(),
                c.mailAttribute(), c.disabledGroupDn(), by).update();
    }

    public void syncResult(String status, String message) {
        jdbc.sql("update directory_connection set last_sync_at = now(), last_sync_status = ?,"
                + " last_sync_message = left(?, 500)").params(status, message).update();
    }

    public List<UUID> organizations() {
        return jdbc.sql("select camhub_directory_orgs()").query(UUID.class).list();
    }

    public List<Mapping> mappings() {
        return jdbc.sql("select id, group_dn, role_id, scope_type, scope_id from directory_group_mapping order by group_dn")
                .query((rs, i) -> new Mapping(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class),
                        rs.getString(4), rs.getObject(5, UUID.class)))
                .list();
    }

    public void replaceMappings(List<Mapping> mappings) {
        jdbc.sql("delete from directory_group_mapping").update();
        for (Mapping m : mappings) {
            jdbc.sql("""
                    insert into directory_group_mapping (organization_id, group_dn, role_id, scope_type, scope_id)
                    values (?, ?, ?, ?, ?) on conflict do nothing
                    """).params(OrganizationContext.require(), m.groupDn().trim(), m.roleId(), m.scopeType(),
                    m.scopeId()).update();
        }
    }

    // ---------------------------------------------------------------- người dùng thư mục

    public Optional<UUID> userByEmail(String email) {
        return jdbc.sql("select id from app_user where lower(email) = lower(?)").param(email).query(UUID.class)
                .optional();
    }

    public UUID insertDirectoryUser(String email, String displayName, String externalId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into app_user (id, organization_id, email, display_name, auth_source, external_id, language)
                values (?, ?, ?, ?, 'DIRECTORY', ?, 'vi')
                """).params(id, OrganizationContext.require(), email.toLowerCase(), displayName, externalId).update();
        return id;
    }

    public void updateDirectoryUser(UUID id, String email, String displayName, String externalId) {
        jdbc.sql("update app_user set email = lower(?), display_name = ?, external_id = ? where id = ?"
                + " and auth_source = 'DIRECTORY'").params(email, displayName, externalId, id).update();
    }

    public List<DirectoryUserRow> directoryUsers() {
        return jdbc.sql("select id, external_id, status from app_user where auth_source = 'DIRECTORY'")
                .query((rs, i) -> new DirectoryUserRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)))
                .list();
    }
}
