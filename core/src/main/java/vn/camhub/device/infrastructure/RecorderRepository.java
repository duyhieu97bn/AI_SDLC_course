package vn.camhub.device.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.camhub.platform.tenancy.OrganizationContext;

/** Bảng recorder (V6_1): đầu ghi NVR/DVR, mỗi kênh là một camera ({@code camera.recorder_id}). */
@Repository
public class RecorderRepository {

    public record Recorder(UUID id, UUID siteId, UUID zoneId, String name, String host, int port, String username,
            String manufacturer, String model, int channelCount, String status, UUID connectorId) {
    }

    private static final String COLUMNS =
            "id, site_id, zone_id, name, host, port, username, manufacturer, model, channel_count, status, connector_id";

    private final JdbcClient jdbc;

    public RecorderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, UUID siteId, UUID zoneId, String name, String host, int port, String username,
            String passwordEnc, String manufacturer, String model, int channelCount, UUID connectorId, UUID by) {
        jdbc.sql("""
                insert into recorder (id, organization_id, site_id, zone_id, name, host, port, username, password_enc,
                                      manufacturer, model, channel_count, connector_id, created_by, status_changed_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                """).params(id, OrganizationContext.require(), siteId, zoneId, name, host, port, username, passwordEnc,
                manufacturer, model, channelCount, connectorId, by).update();
    }

    public Optional<Recorder> find(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from recorder where id = ?").param(id).query(RecorderRepository::map)
                .optional();
    }

    public List<Recorder> all() {
        return jdbc.sql("select " + COLUMNS + " from recorder order by name").query(RecorderRepository::map).list();
    }

    public Optional<String> passwordEnc(UUID id) {
        return jdbc.sql("select password_enc from recorder where id = ?").param(id).query(String.class).optional();
    }

    public List<UUID> organizations() {
        return jdbc.sql("select distinct organization_id from recorder").query(UUID.class).list();
    }

    public Optional<UUID> organizationOf(UUID id) {
        return jdbc.sql("select camhub_recorder_org(?)").param(id).query(UUID.class).optional();
    }

    /** Đổi trạng thái nếu khác và không cũ hơn trạng thái đang lưu; trả về true nếu thật sự đổi. */
    public boolean updateStatus(UUID id, String status, Instant at) {
        return jdbc.sql("""
                update recorder set status = ?, status_changed_at = ?
                where id = ? and status <> ? and (status_changed_at is null or status_changed_at <= ?)
                """).params(status, Timestamp.from(at), id, status, Timestamp.from(at)).update() == 1;
    }

    public List<UUID> channelCameras(UUID id) {
        return jdbc.sql("select id from camera where recorder_id = ? order by channel_no").param(id).query(UUID.class)
                .list();
    }

    private static Recorder map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Recorder(rs.getObject("id", UUID.class), rs.getObject("site_id", UUID.class),
                rs.getObject("zone_id", UUID.class), rs.getString("name"), rs.getString("host"), rs.getInt("port"),
                rs.getString("username"), rs.getString("manufacturer"), rs.getString("model"),
                rs.getInt("channel_count"), rs.getString("status"), rs.getObject("connector_id", UUID.class));
    }
}
