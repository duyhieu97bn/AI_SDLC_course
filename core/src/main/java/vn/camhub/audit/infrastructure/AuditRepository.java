package vn.camhub.audit.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.camhub.platform.audit.AuditableEvent;

/** Bảng audit_log (V2_1): tài khoản ứng dụng chỉ có SELECT/INSERT, trigger chặn UPDATE/DELETE. */
@Repository
public class AuditRepository {

    public record Entry(UUID id, Instant occurredAt, UUID actorId, String actorName, String action, String targetType,
            String targetId, String targetName, String ipAddress, String result, String details) {
    }

    public record Filter(Instant from, Instant to, String action, UUID actorId, String targetId, String result,
            String q) {
    }

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public AuditRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insert(AuditableEvent e, String actorName) {
        Object targetName = e.details().get("targetName");
        jdbc.sql("""
                insert into audit_log (organization_id, occurred_at, actor_id, actor_name, action, target_type, target_id,
                                       target_name, ip_address, result, details)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))
                """).params(e.organizationId(), Timestamp.from(e.occurredAt()), e.actorId(), actorName, e.action(),
                        e.targetType(), e.targetId(), targetName == null ? null : targetName.toString(), e.ipAddress(),
                        e.success() ? "SUCCESS" : "FAILURE", toJson(e)).update();
    }

    public long count(Filter f) {
        List<Object> p = new ArrayList<>();
        return jdbc.sql("select count(*) from audit_log where " + where(f, p)).params(p).query(Long.class).single();
    }

    public List<Entry> search(Filter f, int page, int size) {
        List<Object> p = new ArrayList<>();
        String w = where(f, p);
        p.add(size);
        p.add((long) page * size);
        return jdbc.sql("select * from audit_log where " + w + " order by occurred_at desc limit ? offset ?").params(p)
                .query(AuditRepository::map).list();
    }

    /** Duyệt toàn bộ kết quả theo lô (xuất CSV không giữ cả bảng trong bộ nhớ). */
    public void forEach(Filter f, Consumer<Entry> each) {
        int page = 0;
        List<Entry> batch;
        do {
            batch = search(f, page++, 1000);
            batch.forEach(each);
        } while (batch.size() == 1000);
    }

    public int ensurePartitions(int monthsAhead) {
        return jdbc.sql("select camhub_audit_ensure_partitions(?)").param(monthsAhead).query(Integer.class).single();
    }

    private static String where(Filter f, List<Object> p) {
        String like = f.q() == null || f.q().isBlank() ? null : "%" + f.q().trim().toLowerCase() + "%";
        p.addAll(Arrays.asList(
                f.from() == null ? null : Timestamp.from(f.from()), f.from() == null ? null : Timestamp.from(f.from()),
                f.to() == null ? null : Timestamp.from(f.to()), f.to() == null ? null : Timestamp.from(f.to()),
                f.action(), f.action(), f.actorId(), f.actorId(), f.targetId(), f.targetId(), f.result(), f.result(),
                like, like, like));
        return """
                (cast(? as timestamptz) is null or occurred_at >= ?)
                and (cast(? as timestamptz) is null or occurred_at < ?)
                and (cast(? as text) is null or action = ?)
                and (cast(? as uuid) is null or actor_id = ?)
                and (cast(? as text) is null or target_id = ?)
                and (cast(? as text) is null or result = ?)
                and (cast(? as text) is null or lower(coalesce(actor_name, '')) like ? or lower(coalesce(target_name, '')) like ?)
                """;
    }

    private String toJson(AuditableEvent e) {
        try {
            return json.writeValueAsString(e.details());
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    static Entry map(ResultSet rs, int i) throws SQLException {
        return new Entry(rs.getObject("id", UUID.class), rs.getTimestamp("occurred_at").toInstant(),
                rs.getObject("actor_id", UUID.class), rs.getString("actor_name"), rs.getString("action"),
                rs.getString("target_type"), rs.getString("target_id"), rs.getString("target_name"),
                rs.getString("ip_address"), rs.getString("result"), rs.getString("details"));
    }
}
