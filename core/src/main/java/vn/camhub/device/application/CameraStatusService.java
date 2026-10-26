package vn.camhub.device.application;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import vn.camhub.device.api.CameraChanged;
import vn.camhub.device.infrastructure.CameraRepository;
import vn.camhub.platform.messaging.MessagingTopology;
import vn.camhub.platform.realtime.RealtimePublisher;
import vn.camhub.platform.tenancy.OrganizationContext;

/**
 * Trạng thái kết nối camera (US-04, UC-16): nhận DeviceOnline/DeviceOffline từ Device Gateway qua RabbitMQ,
 * cập nhật DB, đẩy về trình duyệt qua WebSocket {@code /topic/org/{id}/camera-status} (≤ 60 giây, US-04 AC1).
 * Consumer idempotent theo messageId (RabbitMQ có thể gửi lặp, SADD 3.6).
 */
@Service
public class CameraStatusService {

    public static final String QUEUE = "core.device-status";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DeviceEvent(String cameraId, Instant occurredAt, String reason, String source) {
    }

    public record StatusMessage(UUID cameraId, String status, Instant since, String reason) {
    }

    private static final Logger log = LoggerFactory.getLogger(CameraStatusService.class);

    private final CameraRepository cameras;
    private final TransactionTemplate tx;
    private final StringRedisTemplate redis;
    private final RealtimePublisher realtime;
    private final ApplicationEventPublisher events;
    private final vn.camhub.device.infrastructure.RecorderRepository recorders;

    public CameraStatusService(CameraRepository cameras, TransactionTemplate tx, StringRedisTemplate redis,
            RealtimePublisher realtime, ApplicationEventPublisher events,
            vn.camhub.device.infrastructure.RecorderRepository recorders) {
        this.recorders = recorders;
        this.cameras = cameras;
        this.tx = tx;
        this.redis = redis;
        this.realtime = realtime;
        this.events = events;
    }

    @RabbitListener(queues = QUEUE)
    public void onDeviceEvent(@Payload DeviceEvent e, @Header(name = "amqp_receivedRoutingKey") String routingKey,
            @Header(name = "amqp_messageId", required = false) String messageId) {
        if (messageId != null && !firstTime(messageId)) {
            return;
        }
        String status = switch (routingKey) {
            case "device.online" -> "ONLINE";
            case "device.offline" -> "OFFLINE";
            default -> null;
        };
        if (status == null || e.cameraId() == null) {
            return;
        }
        apply(UUID.fromString(e.cameraId()), status, e.occurredAt() == null ? Instant.now() : e.occurredAt(),
                e.reason());
    }

    /** Cập nhật trạng thái; trả về true nếu thật sự đổi (sự kiện cũ hơn trạng thái đang lưu thì bỏ qua). */
    public boolean apply(UUID cameraId, String status, Instant at, String reason) {
        UUID org = cameras.organizationOf(cameraId).orElse(null);
        if (org == null && recorders.organizationOf(cameraId).isPresent()) {
            return applyRecorder(cameraId, recorders.organizationOf(cameraId).get(), status, at, reason);
        }
        if (org == null) {
            log.debug("Bỏ qua sự kiện của camera không còn tồn tại {}", cameraId);
            return false;
        }
        Boolean changed = OrganizationContext.callAs(org, () -> tx.execute(s -> cameras.updateStatus(cameraId, status, at)));
        if (Boolean.TRUE.equals(changed)) {
            realtime.toOrganization(org, "camera-status", new StatusMessage(cameraId, status, at, reason));
            events.publishEvent(new CameraChanged(org, cameraId,
                    "ONLINE".equals(status) ? CameraChanged.Kind.ONLINE : CameraChanged.Kind.OFFLINE));
            log.info("Camera {} chuyển {} lúc {} ({})", cameraId, status, at, reason);
            return true;
        }
        return false;
    }

    /** Đầu ghi đổi trạng thái: mọi kênh (camera) của đầu ghi đổi theo (US-03 AC2). */
    private boolean applyRecorder(UUID recorderId, UUID org, String status, Instant at, String reason) {
        Boolean changed = OrganizationContext.callAs(org,
                () -> tx.execute(s -> recorders.updateStatus(recorderId, status, at)));
        if (!Boolean.TRUE.equals(changed)) {
            return false;
        }
        log.info("Đầu ghi {} chuyển {} ({})", recorderId, status, reason);
        for (UUID cam : OrganizationContext.callAs(org, () -> recorders.channelCameras(recorderId))) {
            apply(cam, status, at, "Đầu ghi: " + (reason == null ? status : reason));
        }
        return true;
    }

    private boolean firstTime(String messageId) {
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent("msg:" + messageId, "1", Duration.ofDays(1)));
        } catch (RuntimeException ex) {
            return true; // Redis lỗi: xử lý lặp vẫn an toàn vì updateStatus chỉ đổi khi khác trạng thái
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Topology {
        @Bean
        Declarables deviceStatusQueue() {
            Queue q = QueueBuilder.durable(QUEUE).build();
            TopicExchange ex = new TopicExchange(MessagingTopology.DEVICE_EVENTS);
            Binding online = BindingBuilder.bind(q).to(ex).with("device.online");
            Binding offline = BindingBuilder.bind(q).to(ex).with("device.offline");
            return new Declarables(q, online, offline);
        }
    }
}
