package vn.camhub.platform.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.camhub.platform.tenancy.OrganizationContext;

/**
 * Ghi sự kiện vào outbox. Bắt buộc gọi trong giao dịch nghiệp vụ đang mở, để sự kiện
 * chỉ tồn tại khi dữ liệu nghiệp vụ đã commit (mẫu transactional outbox, SADD 3.6).
 */
@Component
public class Outbox {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    public Outbox(JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(String exchange, String routingKey, String aggregateType, String aggregateId, Object payload) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into outbox_event
                  (id, organization_id, aggregate_type, aggregate_id, event_type, exchange, routing_key, payload, created_at)
                values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?)
                """,
                id,
                OrganizationContext.current().orElse(null),
                aggregateType,
                aggregateId,
                routingKey, // event_type = routing key, ví dụ "export.requested"
                exchange,
                routingKey,
                toJson(payload),
                Timestamp.from(clock.instant()));
        return id;
    }

    private String toJson(Object payload) {
        if (payload instanceof String s) {
            return s;
        }
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Không tuần tự hóa được payload của sự kiện", e);
        }
    }
}
