package vn.camhub.alerting.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.camhub.alerting.domain.MotionRule;
import vn.camhub.alerting.domain.MotionRule.ScopeType;
import vn.camhub.alerting.domain.MotionRule.Slot;
import vn.camhub.platform.tenancy.OrganizationContext;

/** Bảng motion_rule (V7_1). */
@Repository
public class MotionRuleRepository {

    private static final TypeReference<List<Slot>> SLOTS = new TypeReference<>() {
    };
    private static final String COLUMNS = """
            id, name, enabled, scope_type, scope_id, schedule::text as schedule, cooldown_seconds, channels,
            include_snapshot""";

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public MotionRuleRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<MotionRule> all() {
        return jdbc.sql("select " + COLUMNS + " from motion_rule order by name").query(this::map).list();
    }

    public List<MotionRule> enabled() {
        return jdbc.sql("select " + COLUMNS + " from motion_rule where enabled").query(this::map).list();
    }

    public Optional<MotionRule> find(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from motion_rule where id = ?").param(id).query(this::map).optional();
    }

    public void save(MotionRule r, UUID by) {
        jdbc.sql("""
                insert into motion_rule (id, organization_id, name, enabled, scope_type, scope_id, schedule,
                                         cooldown_seconds, channels, include_snapshot, created_by)
                values (?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?)
                on conflict (id) do update set name = excluded.name, enabled = excluded.enabled,
                    scope_type = excluded.scope_type, scope_id = excluded.scope_id, schedule = excluded.schedule,
                    cooldown_seconds = excluded.cooldown_seconds, channels = excluded.channels,
                    include_snapshot = excluded.include_snapshot, updated_at = now()
                """).params(r.id(), OrganizationContext.require(), r.name(), r.enabled(), r.scopeType().name(),
                r.scopeId(), write(r.schedule()), r.cooldownSeconds(), r.channels().toArray(String[]::new),
                r.includeSnapshot(), by).update();
    }

    public boolean delete(UUID id) {
        return jdbc.sql("delete from motion_rule where id = ?").param(id).update() == 1;
    }

    private MotionRule map(ResultSet rs, int i) throws SQLException {
        String[] ch = (String[]) rs.getArray("channels").getArray();
        return new MotionRule(rs.getObject("id", UUID.class), rs.getString("name"), rs.getBoolean("enabled"),
                ScopeType.valueOf(rs.getString("scope_type")), rs.getObject("scope_id", UUID.class),
                read(rs.getString("schedule")), rs.getInt("cooldown_seconds"), new LinkedHashSet<>(Arrays.asList(ch)),
                rs.getBoolean("include_snapshot"));
    }

    private List<Slot> read(String s) {
        try {
            return s == null ? List.of() : json.readValue(s, SLOTS);
        } catch (Exception e) {
            return List.of();
        }
    }

    private String write(List<Slot> slots) {
        try {
            return json.writeValueAsString(slots == null ? List.of() : slots);
        } catch (Exception e) {
            return "[]";
        }
    }
}
