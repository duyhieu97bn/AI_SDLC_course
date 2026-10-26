package vn.camhub.audit.application;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import vn.camhub.audit.infrastructure.AuditRepository;
import vn.camhub.identity.api.IdentityApi;
import vn.camhub.platform.audit.AuditableEvent;

/**
 * Lưu mọi {@link AuditableEvent} vào audit_log (US-33.2). Listener chạy đồng bộ trong giao dịch của thao tác,
 * nên thao tác và nhật ký cùng commit hoặc cùng hủy; lưu nhật ký lỗi thì thao tác cũng lỗi.
 */
@Service
public class AuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(AuditRecorder.class);
    private static final int NAME_CACHE_MAX = 5000;

    private final AuditRepository repo;
    private final IdentityApi identity;
    private final Map<UUID, String> names = new ConcurrentHashMap<>();

    public AuditRecorder(AuditRepository repo, IdentityApi identity) {
        this.repo = repo;
        this.identity = identity;
    }

    @EventListener
    public void record(AuditableEvent e) {
        if (e.organizationId() == null) {
            // Sự kiện hệ thống không gắn Tổ chức: chỉ còn bản ghi trong log AUDIT (AuditLogFallbackListener)
            return;
        }
        repo.insert(e, actorName(e.actorId()));
    }

    private String actorName(UUID actorId) {
        if (actorId == null) {
            return null;
        }
        if (names.size() > NAME_CACHE_MAX) {
            names.clear();
        }
        return names.computeIfAbsent(actorId, id -> identity.displayName(id).orElse(null));
    }

    /** Họ tên đổi thì nhật ký mới ghi tên mới; nhật ký cũ giữ tên tại thời điểm thao tác. */
    public void forget(UUID userId) {
        names.remove(userId);
    }

    /** Luôn có sẵn phân vùng cho 3 tháng tới. */
    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(cron = "${camhub.audit.partition-cron:0 0 1 1 * *}")
    public void ensurePartitions() {
        int created = Optional.of(repo.ensurePartitions(3)).orElse(0);
        if (created > 0) {
            log.info("Đã tạo {} phân vùng audit_log", created);
        }
    }
}
