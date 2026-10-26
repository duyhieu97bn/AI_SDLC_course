package vn.camhub.device.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.camhub.device.domain.Connector;
import vn.camhub.device.domain.Connector.Status;
import vn.camhub.platform.tenancy.OrganizationContext;

/** Bảng connector, connector_forward, connector_task (V6_1). */
@Repository
public class ConnectorRepository {

    public record Ref(UUID id, UUID organizationId) {
    }

    public record Forward(String host, int targetPort, int port) {
    }

    public record Task(UUID id, String type, int timeoutSeconds, String status, String result, String error) {
    }

    private static final String COLUMNS = """
            id, site_id, name, status, code_expires_at, socks_port, hostname, version, last_seen_at, tunnel_up,
            rx_kbps, tx_kbps, created_at, activated_at""";

    private final JdbcClient jdbc;

    public ConnectorRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, UUID siteId, String name, String codeHash, Instant expires, UUID by) {
        jdbc.sql("""
                insert into connector (id, organization_id, site_id, name, activation_code_hash, code_expires_at,
                                       created_by)
                values (?, ?, ?, ?, ?, ?, ?)
                """).params(id, OrganizationContext.require(), siteId, name, codeHash, ts(expires), by).update();
    }

    public Optional<Connector> find(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from connector where id = ?").param(id)
                .query(ConnectorRepository::map).optional();
    }

    public List<Connector> all() {
        return jdbc.sql("select " + COLUMNS + " from connector where status <> 'REVOKED' order by created_at")
                .query(ConnectorRepository::map).list();
    }

    /** Bộ kết nối đã kích hoạt (đang dùng) của địa điểm. */
    public Optional<Connector> activeForSite(UUID siteId) {
        return jdbc.sql("select " + COLUMNS + " from connector where site_id = ? and status in ('ONLINE', 'OFFLINE')")
                .param(siteId).query(ConnectorRepository::map).optional();
    }

    public Optional<Ref> byCode(String codeHash) {
        return jdbc.sql("select id, organization_id from camhub_connector_by_code(?)").param(codeHash)
                .query((rs, i) -> new Ref(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class))).optional();
    }

    public Optional<UUID> organizationOf(UUID id) {
        return jdbc.sql("select camhub_connector_org(?)").param(id).query(UUID.class).optional();
    }

    public List<Ref> activeAcrossOrganizations() {
        return jdbc.sql("select id, organization_id from camhub_active_connectors()")
                .query((rs, i) -> new Ref(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class))).list();
    }

    public void newCode(UUID id, String codeHash, Instant expires) {
        jdbc.sql("update connector set activation_code_hash = ?, code_expires_at = ? where id = ?")
                .params(codeHash, ts(expires), id).update();
    }

    /** Kích hoạt: xóa mã (dùng 1 lần), lưu bí mật và tài khoản đường hầm. */
    public boolean activate(UUID id, String secretHash, String tunnelPasswordEnc, int socksPort, String hostname,
            String version) {
        return jdbc.sql("""
                update connector set status = 'OFFLINE', activation_code_hash = null, code_expires_at = null,
                    secret_hash = ?, tunnel_password_enc = ?, socks_port = ?, hostname = left(?, 200),
                    version = left(?, 50), activated_at = now()
                where id = ? and status = 'PENDING_ACTIVATION'
                """).params(secretHash, tunnelPasswordEnc, socksPort, hostname, version, id).update() == 1;
    }

    public Optional<String> secretHash(UUID id) {
        return jdbc.sql("select secret_hash from connector where id = ? and status in ('ONLINE', 'OFFLINE')")
                .param(id).query(String.class).optional();
    }

    public Optional<String> tunnelPasswordEnc(UUID id) {
        return jdbc.sql("select tunnel_password_enc from connector where id = ?").param(id).query(String.class)
                .optional();
    }

    /** Ghi nhận heartbeat; trả về trạng thái trước đó. */
    public Status heartbeat(UUID id, String version, boolean tunnelUp, Double rx, Double tx) {
        Status before = jdbc.sql("select status from connector where id = ? for update").param(id)
                .query((rs, i) -> Status.valueOf(rs.getString(1))).single();
        jdbc.sql("""
                update connector set status = 'ONLINE', last_seen_at = now(), version = left(?, 50), tunnel_up = ?,
                    rx_kbps = ?, tx_kbps = ?
                where id = ?
                """).params(version, tunnelUp, rx, tx, id).update();
        return before;
    }

    public List<UUID> staleOnline(Instant before) {
        return jdbc.sql("select id from connector where status = 'ONLINE' and last_seen_at < ?").param(ts(before))
                .query(UUID.class).list();
    }

    public void setStatus(UUID id, Status s) {
        jdbc.sql("update connector set status = ?, tunnel_up = case when ? = 'ONLINE' then tunnel_up else false end"
                + " where id = ?").params(s.name(), s.name(), id).update();
    }

    public void revoke(UUID id) {
        jdbc.sql("""
                update connector set status = 'REVOKED', activation_code_hash = null, secret_hash = null,
                    tunnel_password_enc = null, socks_port = null, tunnel_up = false
                where id = ?
                """).param(id).update();
        jdbc.sql("delete from connector_forward where connector_id = ?").param(id).update();
    }

    public boolean socksPortTaken(int port) {
        return jdbc.sql("select exists (select 1 from connector where socks_port = ?)").param(port)
                .query(Boolean.class).single();
    }

    // ---------------------------------------------------------------- chuyển tiếp cổng

    public List<Forward> forwards(UUID connectorId) {
        return jdbc.sql("select host, target_port, port from connector_forward where connector_id = ? order by port")
                .param(connectorId).query((rs, i) -> new Forward(rs.getString(1), rs.getInt(2), rs.getInt(3))).list();
    }

    public Optional<Integer> forwardPort(UUID connectorId, String host, int targetPort) {
        return jdbc.sql("select port from connector_forward where connector_id = ? and host = ? and target_port = ?")
                .params(connectorId, host, targetPort).query(Integer.class).optional();
    }

    public void insertForward(UUID connectorId, String host, int targetPort, int port) {
        jdbc.sql("""
                insert into connector_forward (organization_id, connector_id, host, target_port, port)
                values (?, ?, ?, ?, ?)
                """).params(OrganizationContext.require(), connectorId, host, targetPort, port).update();
    }

    // ---------------------------------------------------------------- việc của Connector

    public UUID insertTask(UUID connectorId, String type, int timeoutSeconds) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into connector_task (id, organization_id, connector_id, type, timeout_seconds)
                values (?, ?, ?, ?, ?)
                """).params(id, OrganizationContext.require(), connectorId, type, timeoutSeconds).update();
        return id;
    }

    /** Lấy việc chờ và đánh dấu đã giao. */
    public List<Task> takePending(UUID connectorId) {
        return jdbc.sql("""
                update connector_task set status = 'SENT' where connector_id = ? and status = 'PENDING'
                returning id, type, timeout_seconds, status, result::text, error
                """).param(connectorId).query(ConnectorRepository::task).list();
    }

    public Optional<Task> task(UUID id) {
        return jdbc.sql("select id, type, timeout_seconds, status, result::text, error from connector_task where id = ?")
                .param(id).query(ConnectorRepository::task).optional();
    }

    public boolean completeTask(UUID connectorId, UUID taskId, String resultJson, String error) {
        return jdbc.sql("""
                update connector_task set status = case when cast(? as text) is null then 'DONE' else 'FAILED' end,
                    result = cast(? as jsonb), error = left(cast(? as text), 500), completed_at = now()
                where id = ? and connector_id = ? and status in ('PENDING', 'SENT')
                """).params(error, resultJson, error, taskId, connectorId).update() == 1;
    }

    private static Task task(ResultSet rs, int i) throws SQLException {
        return new Task(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getString(5),
                rs.getString(6));
    }

    private static Connector map(ResultSet rs, int i) throws SQLException {
        return new Connector(rs.getObject("id", UUID.class), rs.getObject("site_id", UUID.class), rs.getString("name"),
                Status.valueOf(rs.getString("status")), instant(rs, "code_expires_at"),
                (Integer) rs.getObject("socks_port"), rs.getString("hostname"), rs.getString("version"),
                instant(rs, "last_seen_at"), rs.getBoolean("tunnel_up"), (Double) rs.getObject("rx_kbps"),
                (Double) rs.getObject("tx_kbps"), rs.getTimestamp("created_at").toInstant(),
                instant(rs, "activated_at"));
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}
