package vn.camhub.platform.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Listener chung: ghi mọi {@link AuditableEvent} ra logger {@code AUDIT} (Loki thu lại).
 * Từ S2, module {@code audit} lưu thêm vào bảng {@code audit_log}; listener này vẫn giữ làm bản sao log.
 */
@Component
public class AuditLogFallbackListener {

    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

    @EventListener
    public void on(AuditableEvent e) {
        AUDIT.info("action={} result={} org={} actor={} ip={} target={}:{} details={}", e.action(),
                e.success() ? "SUCCESS" : "FAILURE", e.organizationId(), e.actorId(), e.ipAddress(), e.targetType(),
                e.targetId(), e.details());
    }
}
