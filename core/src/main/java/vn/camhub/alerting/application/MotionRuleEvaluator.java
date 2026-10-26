package vn.camhub.alerting.application;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.springframework.transaction.support.TransactionTemplate;
import vn.camhub.access.api.AccessApi;
import vn.camhub.access.api.Permission;
import vn.camhub.alerting.domain.Alert;
import vn.camhub.alerting.domain.Alert.AlertType;
import vn.camhub.alerting.domain.Alert.Severity;
import vn.camhub.alerting.domain.MotionRule;
import vn.camhub.alerting.infrastructure.AlertRepository;
import vn.camhub.alerting.infrastructure.MotionRuleRepository;
import vn.camhub.device.api.DeviceApi;
import vn.camhub.device.api.DeviceApi.CameraMedia;
import vn.camhub.device.api.DeviceApi.CameraState;
import vn.camhub.notification.api.NotificationApi;
import vn.camhub.notification.api.NotificationApi.Channel;
import vn.camhub.organization.api.OrganizationApi;
import vn.camhub.platform.messaging.MessagingTopology;
import vn.camhub.platform.outbox.Outbox;
import vn.camhub.platform.tenancy.OrganizationContext;

/**
 * Đánh giá quy tắc chuyển động (US-36, UC-51): nhận MotionDetected từ Device Gateway (motionSource = DEVICE), áp
 * quy tắc theo phạm vi và lịch giờ địa phương của địa điểm; chống spam bằng Redis {@code SET NX EX} theo quy tắc và
 * camera (AC3 – sự kiện vẫn được đánh dấu trên timeline bởi module recording); yêu cầu Media Worker chụp ảnh sự kiện,
 * thông báo kênh ngoài chờ tối đa 20 giây để kèm ảnh.
 */
@Component
public class MotionRuleEvaluator {

    public static final String QUEUE = "core.alerting-motion";
    public static final String SNAPSHOT_QUEUE = "core.alerting-snapshots";
    public static final String SNAPSHOT_BUCKET = "camhub-snapshots";
    static final int SNAPSHOT_HOLD_SECONDS = 20;
    private static final Logger log = LoggerFactory.getLogger(MotionRuleEvaluator.class);

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MotionEvent(String cameraId, Boolean state, Instant occurredAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SnapshotResult(UUID snapshotId, UUID organizationId, String bucket, String objectKey, String code,
            String error) {
    }

    /** Thông điệp cho Media Worker (hợp đồng với media-worker snapshot job). */
    public record SnapshotRequested(UUID snapshotId, UUID organizationId, UUID cameraId, String mediaPath, Instant at,
            String bucket, String objectKey) {
    }

    private final MotionRuleRepository rules;
    private final AlertRepository alerts;
    private final DeviceApi devices;
    private final OrganizationApi organizations;
    private final AccessApi access;
    private final NotificationApi notifications;
    private final Outbox outbox;
    private final StringRedisTemplate redis;
    private final TransactionTemplate tx;
    private final Clock clock;

    public MotionRuleEvaluator(MotionRuleRepository rules, AlertRepository alerts, DeviceApi devices,
            OrganizationApi organizations, AccessApi access, NotificationApi notifications, Outbox outbox,
            StringRedisTemplate redis, TransactionTemplate tx, Clock clock) {
        this.rules = rules;
        this.alerts = alerts;
        this.devices = devices;
        this.organizations = organizations;
        this.access = access;
        this.notifications = notifications;
        this.outbox = outbox;
        this.redis = redis;
        this.tx = tx;
        this.clock = clock;
    }

    @RabbitListener(queues = QUEUE)
    public void onMotion(@Payload MotionEvent e, @Header(name = "amqp_messageId", required = false) String messageId) {
        if (e.cameraId() == null || !Boolean.TRUE.equals(e.state())) {
            return;
        }
        if (messageId != null && !Boolean.TRUE.equals(redis.opsForValue()
                .setIfAbsent("msg:alert-motion:" + messageId, "1", Duration.ofDays(1)))) {
            return;
        }
        UUID cameraId = UUID.fromString(e.cameraId());
        devices.organizationOf(cameraId).ifPresent(org -> OrganizationContext.runAs(org,
                () -> evaluate(cameraId, e.occurredAt() == null ? clock.instant() : e.occurredAt())));
    }

    /** Đánh giá mọi quy tắc cho một lần chuyển động; trả về id các cảnh báo đã tạo (test). */
    public List<UUID> evaluate(UUID cameraId, Instant at) {
        CameraState cam = devices.state(cameraId).orElse(null);
        CameraMedia media = devices.media(cameraId).orElse(null);
        if (cam == null || media == null) {
            return List.of();
        }
        ZoneId tz = ZoneId.of(organizations.siteTimezone(cam.siteId()));
        List<UUID> created = new java.util.ArrayList<>();
        for (MotionRule rule : rules.enabled()) {
            if (!rule.covers(cam.siteId(), cam.zoneId(), cameraId) || !rule.activeAt(at.atZone(tz))) {
                continue; // AC2: ngoài khung giờ thì không gửi
            }
            if (rule.cooldownSeconds() > 0 && !Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(
                    "alert:motion:" + rule.id() + ":" + cameraId, at.toString(),
                    Duration.ofSeconds(rule.cooldownSeconds())))) {
                continue; // AC3: trong thời gian chờ chống spam
            }
            raise(rule, cam, media, at).ifPresent(created::add);
        }
        return created;
    }

    private java.util.Optional<UUID> raise(MotionRule rule, CameraState cam, CameraMedia media, Instant at) {
        String site = organizations.siteName(cam.siteId()).orElse("");
        String title = "Chuyển động: " + cam.name() + (site.isEmpty() ? "" : " (" + site + ")");
        Map<String, Object> details = new HashMap<>(Map.of("cameraName", cam.name(), "siteName", site,
                "ruleName", rule.name(), "at", at.toString()));
        Set<UUID> recipients = access.usersWithPermissionOnCamera(Permission.VIEW_LIVE, cam.cameraId());
        Set<Channel> channels = EnumSet.of(Channel.WEB);
        rule.channels().forEach(c -> {
            try {
                channels.add(Channel.valueOf(c));
            } catch (IllegalArgumentException ignored) {
                // kênh không còn hỗ trợ: bỏ qua
            }
        });
        UUID org = OrganizationContext.require();
        return tx.execute(s -> {
            var id = alerts.insertIfNoneOpen(AlertType.MOTION, Severity.WARNING,
                    Alert.motionKey(cam.cameraId(), rule.id(), at), cam.cameraId(), cam.siteId(), title, details, at);
            id.ifPresent(alertId -> {
                alerts.setRule(alertId, rule.id(), rule.includeSnapshot() ? "PENDING" : null);
                if (rule.includeSnapshot()) {
                    outbox.append(MessagingTopology.JOBS, "snapshot.requested", "Alert", alertId.toString(),
                            new SnapshotRequested(alertId, org, cam.cameraId(), media.mediaPath(), at,
                                    SNAPSHOT_BUCKET, org + "/" + cam.cameraId() + "/" + alertId + ".jpg"));
                }
                notifications.send(recipients, new NotificationApi.Message(title,
                        "Quy tắc \"" + rule.name() + "\" lúc " + at.atZone(ZoneId.of(organizations.siteTimezone(
                                cam.siteId()))).toLocalDateTime().withNano(0), "/alerts?id=" + alertId, alertId),
                        channels, rule.includeSnapshot() ? SNAPSHOT_HOLD_SECONDS : 0);
                log.info("Cảnh báo chuyển động {} ({}) → {} người nhận", alertId, cam.name(), recipients.size());
            });
            return id;
        });
    }

    /** Kết quả chụp ảnh sự kiện từ Media Worker: gắn ảnh vào cảnh báo và thông báo đang chờ. */
    @RabbitListener(queues = SNAPSHOT_QUEUE)
    public void onSnapshot(@Payload SnapshotResult r, @Header(name = "amqp_receivedRoutingKey") String routingKey) {
        if (r.snapshotId() == null || r.organizationId() == null) {
            return;
        }
        OrganizationContext.runAs(r.organizationId(), () -> tx.executeWithoutResult(s -> {
            if ("snapshot.created".equals(routingKey)) {
                alerts.snapshotResult(r.snapshotId(), "READY", r.bucket(), r.objectKey());
                notifications.attachSnapshot(r.snapshotId(), r.bucket(), r.objectKey());
            } else if ("snapshot.failed".equals(routingKey)) {
                log.warn("Không chụp được ảnh cho cảnh báo {}: {}", r.snapshotId(), r.error());
                alerts.snapshotResult(r.snapshotId(), "FAILED", null, null);
                notifications.releaseHeld(r.snapshotId());
            }
        }));
    }

    @Configuration(proxyBeanMethods = false)
    static class Topology {
        @Bean
        Declarables alertingMotionQueues() {
            Queue motion = QueueBuilder.durable(QUEUE).build();
            Queue snaps = QueueBuilder.durable(SNAPSHOT_QUEUE).build();
            return new Declarables(motion, snaps,
                    BindingBuilder.bind(motion).to(new TopicExchange(MessagingTopology.DEVICE_EVENTS))
                            .with("motion.detected"),
                    BindingBuilder.bind(snaps).to(new TopicExchange(MessagingTopology.JOB_RESULTS)).with("snapshot.*"));
        }
    }
}
