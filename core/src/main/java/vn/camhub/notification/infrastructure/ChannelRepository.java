package vn.camhub.notification.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.camhub.platform.tenancy.OrganizationContext;

/** Bảng user_channel_binding, notification_channel_config (V7_1). */
@Repository
public class ChannelRepository {

    public record Binding(UUID id, UUID userId, String channel, String address, boolean enabled) {
    }

    public record ChannelConfig(String channel, boolean enabled, boolean hasSecret) {
    }

    private final JdbcClient jdbc;

    public ChannelRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Binding> bindings(UUID userId) {
        return jdbc.sql("select id, user_id, channel, address, enabled from user_channel_binding where user_id = ?"
                + " order by channel").param(userId)
                .query((rs, i) -> new Binding(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getString(4), rs.getBoolean(5)))
                .list();
    }

    public Optional<String> address(UUID userId, String channel) {
        return jdbc.sql("select address from user_channel_binding where user_id = ? and channel = ? and enabled")
                .params(userId, channel).query(String.class).optional();
    }

    public void upsertBinding(UUID userId, String channel, String address, boolean enabled) {
        jdbc.sql("""
                insert into user_channel_binding (organization_id, user_id, channel, address, enabled)
                values (?, ?, ?, ?, ?)
                on conflict (user_id, channel) do update set address = excluded.address, enabled = excluded.enabled
                """).params(OrganizationContext.require(), userId, channel, address, enabled).update();
    }

    public void deleteBinding(UUID userId, String channel) {
        jdbc.sql("delete from user_channel_binding where user_id = ? and channel = ?").params(userId, channel).update();
    }

    public List<ChannelConfig> configs() {
        return jdbc.sql("select channel, enabled, secret_enc is not null from notification_channel_config"
                + " order by channel")
                .query((rs, i) -> new ChannelConfig(rs.getString(1), rs.getBoolean(2), rs.getBoolean(3))).list();
    }

    public boolean enabled(String channel) {
        return jdbc.sql("select enabled from notification_channel_config where channel = ?").param(channel)
                .query(Boolean.class).optional().orElse(false);
    }

    public Optional<String> secretEnc(String channel) {
        return jdbc.sql("select secret_enc from notification_channel_config where channel = ?").param(channel)
                .query(String.class).optional();
    }

    /** {@code secretEnc} null = giữ bí mật cũ. */
    public void saveConfig(String channel, boolean enabled, String secretEnc, UUID by) {
        jdbc.sql("""
                insert into notification_channel_config (organization_id, channel, enabled, secret_enc, updated_by)
                values (?, ?, ?, ?, ?)
                on conflict (organization_id, channel) do update set enabled = excluded.enabled,
                    secret_enc = coalesce(excluded.secret_enc, notification_channel_config.secret_enc),
                    updated_by = excluded.updated_by, updated_at = now()
                """).params(OrganizationContext.require(), channel, enabled, secretEnc, by).update();
    }
}
