package vn.camhub.recording.application;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import vn.camhub.platform.messaging.MessagingTopology;

/**
 * Nhận MotionDetected từ Device Gateway (sự kiện của camera, CD-03) và ghi mốc chuyển động cho ghi theo sự kiện và
 * timeline. Hàng đợi riêng của module recording; module alerting (S5) có hàng đợi riêng cho cùng sự kiện.
 */
@Component
public class MotionMarker {

    public static final String QUEUE = "core.recording-motion";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MotionEvent(String cameraId, Boolean state, Instant occurredAt) {
    }

    private final RecordingService recording;
    private final StringRedisTemplate redis;

    public MotionMarker(RecordingService recording, StringRedisTemplate redis) {
        this.recording = recording;
        this.redis = redis;
    }

    @RabbitListener(queues = QUEUE)
    public void onMotion(@Payload MotionEvent e, @Header(name = "amqp_messageId", required = false) String messageId) {
        if (e.cameraId() == null || !Boolean.TRUE.equals(e.state())) {
            return;
        }
        if (messageId != null && !Boolean.TRUE.equals(redis.opsForValue()
                .setIfAbsent("msg:rec:" + messageId, "1", Duration.ofDays(1)))) {
            return;
        }
        recording.markMotion(UUID.fromString(e.cameraId()), e.occurredAt() == null ? Instant.now() : e.occurredAt());
    }

    @Configuration(proxyBeanMethods = false)
    static class Topology {
        @Bean
        Declarables recordingMotionQueue() {
            Queue q = QueueBuilder.durable(QUEUE).build();
            Binding b = BindingBuilder.bind(q).to(new TopicExchange(MessagingTopology.DEVICE_EVENTS))
                    .with("motion.detected");
            return new Declarables(q, b);
        }
    }
}
