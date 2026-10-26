package vn.camhub.liveview.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import vn.camhub.device.api.DeviceApi;
import vn.camhub.device.api.DeviceControlPort.TalkStatus;
import vn.camhub.platform.audit.AuditableEvent;
import vn.camhub.platform.tenancy.OrganizationContext;

/**
 * Nhật ký TALK_AUDIO kèm thời lượng (US-15, BR-13). Phiên đang mở nằm trong Redis để bản Core nào cũng kết thúc
 * được; ghi nhật ký đúng một lần cho mỗi phiên: bản nào xóa được khóa phiên ({@code DEL} trả 1) thì bản đó ghi.
 * Người dùng nhả nút thì ghi ngay ({@link TalkService#end}); trình duyệt đóng ngang hoặc mất mạng thì việc quét định
 * kỳ đọc trạng thái phiên ở Gateway và ghi khi phiên đã kết thúc.
 */
@Service
public class TalkAuditService {

    private static final Logger log = LoggerFactory.getLogger(TalkAuditService.class);
    private static final String KEY = "talk:session:";
    private static final String INDEX = "talk:sessions";
    /** Lớn hơn hạn nối + độ dài tối đa một phiên ở Gateway (30 giây + 5 phút). */
    static final Duration PENDING_TTL = Duration.ofMinutes(15);

    /** Phiên Core đã mở, chờ ghi nhật ký. */
    public record Pending(String sessionId, UUID userId, UUID organizationId, UUID cameraId, String cameraName,
            String ipAddress, Instant openedAt) {
    }

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final DeviceApi devices;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;

    public TalkAuditService(StringRedisTemplate redis, ObjectMapper json, DeviceApi devices,
            ApplicationEventPublisher events, TransactionTemplate tx) {
        this.redis = redis;
        this.json = json;
        this.devices = devices;
        this.events = events;
        this.tx = tx;
    }

    void register(Pending p) {
        try {
            redis.opsForValue().set(KEY + p.sessionId(), json.writeValueAsString(p), PENDING_TTL);
            redis.opsForSet().add(INDEX, p.sessionId());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    Optional<Pending> find(String sessionId) {
        String raw = redis.opsForValue().get(KEY + sessionId);
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(raw, Pending.class));
        } catch (JsonProcessingException e) {
            log.warn("Phiên đàm thoại {} lưu sai định dạng: {}", sessionId, e.getOriginalMessage());
            return Optional.empty();
        }
    }

    /** Ghi TALK_AUDIO nếu bản Core này giành được phiên; {@code status} rỗng khi Gateway không còn giữ phiên. */
    boolean complete(Pending p, Optional<TalkStatus> status) {
        redis.opsForSet().remove(INDEX, p.sessionId());
        if (!Boolean.TRUE.equals(redis.delete(KEY + p.sessionId()))) {
            return false;
        }
        Map<String, Object> details = new HashMap<>();
        details.put("cameraName", p.cameraName());
        details.put("durationSeconds", status.map(TalkStatus::durationSeconds).orElse(0.0));
        details.put("audioSeconds", status.map(TalkStatus::audioSeconds).orElse(0.0));
        details.put("state", status.map(TalkStatus::state).orElse("UNKNOWN"));
        status.map(TalkStatus::error).ifPresent(err -> details.put("error", err));
        Instant at = status.map(TalkStatus::startedAt).orElse(p.openedAt());
        AuditableEvent event = new AuditableEvent(p.organizationId(), p.userId(), "TALK_AUDIO", "CAMERA",
                p.cameraId().toString(), status.map(s -> s.error() == null).orElse(true), p.ipAddress(), details,
                at == null ? p.openedAt() : at);
        OrganizationContext.runAs(p.organizationId(), () -> tx.executeWithoutResult(s -> events.publishEvent(event)));
        return true;
    }

    /** Phiên mà trình duyệt không báo kết thúc (đóng tab, mất mạng): ghi khi Gateway báo đã xong. */
    @Scheduled(fixedDelayString = "${camhub.talk.sweep-interval:15000}", initialDelay = 15_000)
    public void sweep() {
        Set<String> ids = redis.opsForSet().members(INDEX);
        if (ids == null) {
            return;
        }
        for (String id : ids) {
            Optional<Pending> pending = find(id);
            if (pending.isEmpty()) {
                redis.opsForSet().remove(INDEX, id);
                continue;
            }
            try {
                Optional<TalkStatus> status = devices.talkStatus(id);
                boolean open = status.map(s -> "READY".equals(s.state()) || "ACTIVE".equals(s.state())).orElse(false);
                if (!open) {
                    complete(pending.get(), status);
                }
            } catch (RuntimeException e) {
                log.warn("Không đọc được trạng thái phiên đàm thoại {}: {}", id.substring(0, 6), e.toString());
            }
        }
    }
}
