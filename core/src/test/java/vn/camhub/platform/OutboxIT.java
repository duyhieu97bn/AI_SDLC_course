package vn.camhub.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import vn.camhub.IntegrationTestSupport;
import vn.camhub.platform.messaging.MessagingTopology;
import vn.camhub.platform.outbox.Outbox;
import vn.camhub.platform.outbox.OutboxEvent;
import vn.camhub.platform.outbox.OutboxPublisher;
import vn.camhub.platform.outbox.OutboxTransport;
import vn.camhub.platform.outbox.RabbitOutboxTransport;

/** Tiêu chí TS-04 "Không mất sự kiện khi broker lỗi". */
@Import(OutboxIT.FlakyBrokerConfig.class)
class OutboxIT extends IntegrationTestSupport {

    /** Bọc transport thật; bật {@code down} để giả lập broker dừng. */
    static class FlakyTransport implements OutboxTransport {
        final OutboxTransport real;
        final AtomicBoolean down = new AtomicBoolean();

        FlakyTransport(OutboxTransport real) {
            this.real = real;
        }

        @Override
        public void send(OutboxEvent event) {
            if (down.get()) {
                throw new org.springframework.amqp.AmqpConnectException(new java.net.ConnectException("broker down"));
            }
            real.send(event);
        }
    }

    @TestConfiguration
    static class FlakyBrokerConfig {
        @Bean
        @Primary
        FlakyTransport flakyTransport(RabbitOutboxTransport real) {
            return new FlakyTransport(real);
        }
    }

    static final String ROUTING_KEY = "test.happened";

    @Autowired
    Outbox outbox;
    @Autowired
    OutboxPublisher publisher;
    @Autowired
    FlakyTransport transport;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    RabbitTemplate rabbit;
    @Autowired
    AmqpAdmin admin;

    String queue;

    @BeforeEach
    void bindTestQueue() {
        transport.down.set(false);
        publisher.publishPending(); // dọn sự kiện còn sót của test trước
        queue = admin.declareQueue(new Queue("", false, true, true));
        Binding b = BindingBuilder.bind(new Queue(queue)).to(new TopicExchange(MessagingTopology.JOBS)).with(ROUTING_KEY);
        admin.declareBinding(b);
    }

    @Test
    void eventIsPublishedAfterCommitWithMessageId() {
        UUID id = appendInTransaction("ok");

        publisher.publishPending(); // lượt quét định kỳ có thể đã gửi trước

        Message m = rabbit.receive(queue, 5_000);
        assertThat(m).isNotNull();
        assertThat(m.getMessageProperties().getMessageId()).isEqualTo(id.toString());
        assertThat(new String(m.getBody())).containsPattern("\"step\":\\s*\"ok\""); // jsonb chuẩn hóa khoảng trắng
        assertThat(publishedAt(id)).isNotNull();
    }

    @Test
    void eventSurvivesBrokerOutageAndIsSentAfterRecovery() {
        transport.down.set(true);
        UUID id = appendInTransaction("outage");

        assertThat(publisher.publishPending()).isZero();
        Map<String, Object> row = jdbc.queryForMap("select attempts, published_at, last_error from outbox_event where id = ?", id);
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat(row.get("published_at")).isNull();
        assertThat((String) row.get("last_error")).contains("broker down");

        transport.down.set(false);
        jdbc.update("update outbox_event set next_attempt_at = now() where id = ?", id); // bỏ qua thời gian chờ backoff
        // Lượt quét định kỳ (mỗi giây) có thể gửi trước lượt gọi tay; kiểm kết quả, không kiểm ai gửi
        publisher.publishPending();

        Message m = rabbit.receive(queue, 5_000);
        assertThat(m).isNotNull();
        assertThat(m.getMessageProperties().getMessageId()).isEqualTo(id.toString());
        assertThat(publishedAt(id)).isNotNull();
    }

    @Test
    void rolledBackTransactionLeavesNoEvent() {
        UUID[] holder = new UUID[1];
        tx.executeWithoutResult(status -> {
            holder[0] = outbox.append(MessagingTopology.JOBS, ROUTING_KEY, "Test", "1", Map.of("step", "rollback"));
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where id = ?", Long.class, holder[0])).isZero();
        assertThat(publisher.publishPending()).isZero();
    }

    private UUID appendInTransaction(String step) {
        return tx.execute(s -> outbox.append(MessagingTopology.JOBS, ROUTING_KEY, "Test", "1", Map.of("step", step)));
    }

    private Object publishedAt(UUID id) {
        return jdbc.queryForObject("select published_at from outbox_event where id = ?", Object.class, id);
    }
}
