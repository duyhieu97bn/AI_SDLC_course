package vn.camhub.export.application;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import vn.camhub.platform.tenancy.OrganizationContext;

/**
 * Nhận kết quả xuất clip từ Media Worker: {@code export.progress}, {@code export.completed}, {@code export.failed}
 * trên exchange {@code job.results}. Idempotent theo messageId; trạng thái chỉ đổi theo máy trạng thái ExportJob.
 */
@Component
public class ExportResultListener {

    public static final String QUEUE = "core.export-results";
    private static final Logger log = LoggerFactory.getLogger(ExportResultListener.class);

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExportResult(UUID exportId, UUID organizationId, Long sizeBytes, String sha256, String code,
            String error) {
    }

    private final ExportService exports;
    private final StringRedisTemplate redis;

    public ExportResultListener(ExportService exports, StringRedisTemplate redis) {
        this.exports = exports;
        this.redis = redis;
    }

    @RabbitListener(queues = QUEUE)
    public void onResult(@Payload ExportResult r, @Header(name = "amqp_receivedRoutingKey") String routingKey,
            @Header(name = "amqp_messageId", required = false) String messageId) {
        if (r.exportId() == null || r.organizationId() == null) {
            return;
        }
        if (messageId != null && !firstTime(messageId)) {
            return;
        }
        log.info("Kết quả xuất clip {}: {}", r.exportId(), routingKey);
        OrganizationContext.runAs(r.organizationId(), () -> {
            switch (routingKey) {
                case "export.progress" -> exports.onProgress(r.exportId());
                case "export.completed" -> exports.onCompleted(r.exportId(),
                        r.sizeBytes() == null ? 0 : r.sizeBytes(), r.sha256());
                case "export.failed" -> exports.onFailed(r.exportId(), r.code(),
                        r.error() == null ? "Không xuất được clip" : r.error());
                default -> log.debug("Bỏ qua kết quả {}", routingKey);
            }
        });
    }

    private boolean firstTime(String messageId) {
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent("msg:export:" + messageId, "1",
                    Duration.ofDays(1)));
        } catch (RuntimeException e) {
            return true; // trạng thái chỉ đổi một chiều nên xử lý lặp vẫn an toàn
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Topology {
        @Bean
        Declarables exportResultQueue() {
            Queue q = QueueBuilder.durable(QUEUE).build();
            Binding b = BindingBuilder.bind(q).to(new TopicExchange(MessagingTopology.JOB_RESULTS)).with("export.*");
            return new Declarables(q, b);
        }
    }
}
