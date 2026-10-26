package vn.camhub.alerting.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.camhub.alerting.domain.Alert;
import vn.camhub.alerting.domain.Alert.AlertType;
import vn.camhub.alerting.domain.Alert.Severity;
import vn.camhub.alerting.domain.Alert.Status;
import vn.camhub.alerting.domain.AlertRule;
import vn.camhub.platform.tenancy.OrganizationContext;

/** Bảng alert_rule, alert, alert_status_change (V5_2). */
@Repository
public class AlertRepository {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    private static final String COLUMNS = """
            id, type, severity, status, source_key, camera_id, site_id, title, details::text as details, occurred_at,
            recovered_at, assignee_id, closed_at, outcome, outcome_note, snapshot_status""";

    public record Handover(UUID fromUser, UUID toUser, String reason, Instant at) {
    }

    public record StatusChange(Status from, Status to, UUID actorId, String note, Instant at) {
    }

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public AlertRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<UUID> organizations() {
        return jdbc.sql("select camhub_organization_ids()").query(UUID.class).list();
    }

    // ---------------------------------------------------------------- quy tắc

    public AlertRule rule(AlertType type) {
        return jdbc.sql("select enabled, params::text, channels from alert_rule where type = ?").param(type.name())
                .query((rs, i) -> new AlertRule(type, rs.getBoolean(1), read(rs.getString(2)),
                        new LinkedHashSet<>(Arrays.asList(strings(rs.getArray(3))))))
                .optional().orElseGet(() -> AlertRule.defaults(type));
    }

    public void saveRule(AlertRule r, UUID by) {
        jdbc.sql("""
                insert into alert_rule (organization_id, type, enabled, params, channels, updated_by)
                values (?, ?, ?, cast(? as jsonb), ?, ?)
                on conflict (organization_id, type) do update set enabled = excluded.enabled, params = excluded.params,
                    channels = excluded.channels, updated_by = excluded.updated_by, updated_at = now()
                """).params(OrganizationContext.require(), r.type().name(), r.enabled(), write(r.params()),
                r.channels().toArray(String[]::new), by).update();
    }

    // ---------------------------------------------------------------- cảnh báo

    /** Tạo cảnh báo nếu nguồn chưa có cảnh báo đang mở (chỉ mục duy nhất); trả về id khi đã tạo. */
    public Optional<UUID> insertIfNoneOpen(AlertType type, Severity severity, String sourceKey, UUID cameraId,
            UUID siteId, String title, Map<String, Object> details, Instant at) {
        UUID id = UUID.randomUUID();
        int n = jdbc.sql("""
                insert into alert (id, organization_id, type, severity, source_key, camera_id, site_id, title, details,
                                   occurred_at)
                values (?, ?, ?, ?, ?, ?, ?, left(?, 300), cast(? as jsonb), ?)
                on conflict (organization_id, source_key) where status in ('NEW', 'IN_PROGRESS') do nothing
                """).params(id, OrganizationContext.require(), type.name(), severity.name(), sourceKey, cameraId, siteId,
                title, write(details), Timestamp.from(at)).update();
        if (n == 0) {
            return Optional.empty();
        }
        insertChange(id, null, Status.NEW, null, null);
        return Optional.of(id);
    }

    public Optional<Alert> openBySource(String sourceKey) {
        return jdbc.sql("select " + COLUMNS + " from alert where source_key = ? and status in ('NEW', 'IN_PROGRESS')")
                .param(sourceKey).query(this::map).optional();
    }

    public List<Alert> open(AlertType type) {
        return jdbc.sql("select " + COLUMNS + " from alert where type = ? and status in ('NEW', 'IN_PROGRESS')")
                .param(type.name()).query(this::map).list();
    }

    public void escalate(UUID id, Severity severity, String title, Map<String, Object> details) {
        jdbc.sql("update alert set severity = ?, title = left(?, 300), details = cast(? as jsonb) where id = ?")
                .params(severity.name(), title, write(details), id).update();
    }

    public boolean recover(UUID id, Instant at) {
        Optional<Status> from = jdbc.sql("select status from alert where id = ? and status in ('NEW', 'IN_PROGRESS')"
                + " for update").param(id).query((rs, i) -> Status.valueOf(rs.getString(1))).optional();
        if (from.isEmpty()) {
            return false;
        }
        jdbc.sql("update alert set status = 'RECOVERED', recovered_at = ? where id = ?")
                .params(Timestamp.from(at), id).update();
        insertChange(id, from.get(), Status.RECOVERED, null, null);
        return true;
    }

    public Optional<Alert> find(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from alert where id = ?").param(id).query(this::map).optional();
    }

    /** Cảnh báo gần đây (30 ngày), lọc trạng thái/loại; quyền xem lọc ở tầng ứng dụng. */
    public List<Alert> recent(boolean openOnly, AlertType type, Instant since, int limit) {
        return jdbc.sql("select " + COLUMNS + " from alert where occurred_at >= ?"
                + (openOnly ? " and status in ('NEW', 'IN_PROGRESS')" : "")
                + " and (cast(? as text) is null or type = ?) order by occurred_at desc limit ?")
                .params(Timestamp.from(since), type == null ? null : type.name(), type == null ? null : type.name(),
                        limit)
                .query(this::map).list();
    }

    public List<Alert> allOpen() {
        return jdbc.sql("select " + COLUMNS + " from alert where status in ('NEW', 'IN_PROGRESS')").query(this::map)
                .list();
    }

    public List<StatusChange> history(UUID alertId) {
        return jdbc.sql("select from_status, to_status, actor_id, note, changed_at from alert_status_change"
                + " where alert_id = ? order by changed_at").param(alertId)
                .query((rs, i) -> new StatusChange(rs.getString(1) == null ? null : Status.valueOf(rs.getString(1)),
                        Status.valueOf(rs.getString(2)), rs.getObject(3, UUID.class), rs.getString(4),
                        rs.getTimestamp(5).toInstant()))
                .list();
    }

    // ---------------------------------------------------------------- chuyển động (US-36)

    public void setRule(UUID alertId, UUID ruleId, String snapshotStatus) {
        jdbc.sql("update alert set rule_id = ?, snapshot_status = ? where id = ?").params(ruleId, snapshotStatus, alertId)
                .update();
    }

    public void snapshotResult(UUID alertId, String status, String bucket, String key) {
        jdbc.sql("update alert set snapshot_status = ?, snapshot_bucket = ?, snapshot_object_key = ? where id = ?")
                .params(status, bucket, key, alertId).update();
    }

    public Optional<String[]> snapshot(UUID alertId) {
        return jdbc.sql("select snapshot_bucket, snapshot_object_key from alert where id = ?"
                + " and snapshot_status = 'READY'").param(alertId)
                .query((rs, i) -> new String[] {rs.getString(1), rs.getString(2)}).optional();
    }

    // ---------------------------------------------------------------- xử lý, chuyển giao (US-37)

    /** Đổi trạng thái có điều kiện trạng thái hiện tại, ghi lịch sử; trả về false nếu cảnh báo đã đổi trạng thái. */
    public boolean transition(UUID id, Status from, Status to, UUID actor, UUID assignee, String outcome, String note) {
        int n = jdbc.sql("""
                update alert set status = ?, assignee_id = coalesce(?, assignee_id),
                    closed_at = case when ? = 'CLOSED' then now() else closed_at end,
                    outcome = coalesce(?, outcome), outcome_note = coalesce(?, outcome_note)
                where id = ? and status = ?
                """).params(to.name(), assignee, to.name(), outcome, note, id, from.name()).update();
        if (n == 1) {
            insertChange(id, from, to, actor, note);
        }
        return n == 1;
    }

    public boolean reassign(UUID id, UUID from, UUID to, String reason) {
        int n = jdbc.sql("update alert set assignee_id = ? where id = ? and status = 'IN_PROGRESS' and assignee_id = ?")
                .params(to, id, from).update();
        if (n == 1) {
            jdbc.sql("""
                    insert into alert_handover (organization_id, alert_id, from_user, to_user, reason)
                    values (?, ?, ?, ?, ?)
                    """).params(OrganizationContext.require(), id, from, to, reason).update();
            insertChange(id, Status.IN_PROGRESS, Status.IN_PROGRESS, from, "Chuyển giao: " + reason);
        }
        return n == 1;
    }

    public List<Handover> handovers(UUID alertId) {
        return jdbc.sql("select from_user, to_user, reason, handed_at from alert_handover where alert_id = ?"
                + " order by handed_at").param(alertId)
                .query((rs, i) -> new Handover(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getTimestamp(4).toInstant()))
                .list();
    }

    void insertChange(UUID alertId, Status from, Status to, UUID actor, String note) {
        jdbc.sql("""
                insert into alert_status_change (organization_id, alert_id, from_status, to_status, actor_id, note)
                values (?, ?, ?, ?, ?, ?)
                """).params(OrganizationContext.require(), alertId, from == null ? null : from.name(), to.name(), actor,
                note).update();
    }

    private Alert map(ResultSet rs, int i) throws SQLException {
        return new Alert(rs.getObject("id", UUID.class), AlertType.valueOf(rs.getString("type")),
                Severity.valueOf(rs.getString("severity")), Status.valueOf(rs.getString("status")),
                rs.getString("source_key"), rs.getObject("camera_id", UUID.class), rs.getObject("site_id", UUID.class),
                rs.getString("title"), read(rs.getString("details")), rs.getTimestamp("occurred_at").toInstant(),
                instant(rs, "recovered_at"), rs.getObject("assignee_id", UUID.class), instant(rs, "closed_at"),
                rs.getString("outcome"), rs.getString("outcome_note"), rs.getString("snapshot_status"));
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private static String[] strings(Array a) throws SQLException {
        return a == null ? new String[0] : (String[]) a.getArray();
    }

    private Map<String, Object> read(String s) {
        try {
            return s == null ? Map.of() : json.readValue(s, MAP);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String write(Map<String, Object> m) {
        try {
            return json.writeValueAsString(m == null ? Map.of() : m);
        } catch (Exception e) {
            return "{}";
        }
    }
}
