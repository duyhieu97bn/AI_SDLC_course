package vn.camhub.platform.outbox;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/** Gửi qua RabbitMQ với publisher confirm: chỉ coi là đã gửi khi broker xác nhận. */
@Component
public class RabbitOutboxTransport implements OutboxTransport {

    static final long CONFIRM_TIMEOUT_MS = 5_000;

    private final RabbitTemplate rabbit;

    public RabbitOutboxTransport(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
    }

    @Override
    public void send(OutboxEvent event) {
        MessageProperties props = new MessageProperties();
        props.setMessageId(event.id().toString());
        props.setType(event.eventType());
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setContentEncoding(StandardCharsets.UTF_8.name());
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        props.setTimestamp(Date.from(event.createdAt()));
        if (event.organizationId() != null) {
            props.setHeader("x-organization-id", event.organizationId().toString());
        }
        props.setHeader("x-aggregate", event.aggregateType() + ":" + event.aggregateId());
        Message message = new Message(event.payload().getBytes(StandardCharsets.UTF_8), props);

        rabbit.invoke(ops -> {
            ops.send(event.exchange(), event.routingKey(), message);
            ops.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MS);
            return null;
        });
    }
}
