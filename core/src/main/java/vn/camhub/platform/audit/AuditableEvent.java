package vn.camhub.platform.audit;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import vn.camhub.platform.tenancy.OrganizationContext;

/**
 * Sự kiện cần ghi nhật ký kiểm toán (BR-30). Module nghiệp vụ phát qua
 * {@code ApplicationEventPublisher}; module {@code audit} lắng nghe và lưu vào {@code audit_log}
 * trong cùng giao dịch, nên thao tác và nhật ký cùng thành công hoặc cùng hủy.
 *
 * @param action     mã hành động (AuditAction), ví dụ {@code LOGIN}, {@code VIEW_PLAYBACK}, {@code EXPORT_VIDEO}
 * @param targetType loại đối tượng bị tác động, ví dụ {@code CAMERA}
 * @param success    kết quả thao tác (ActionResult SUCCESS / FAILURE)
 * @param details    thông tin thêm; không chứa mật khẩu hay bí mật
 */
public record AuditableEvent(
        UUID organizationId,
        UUID actorId,
        String action,
        String targetType,
        String targetId,
        boolean success,
        String ipAddress,
        Map<String, Object> details,
        Instant occurredAt) {

    public AuditableEvent {
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    public static AuditableEvent of(UUID actorId, String action, String targetType, String targetId,
            Map<String, Object> details) {
        return new AuditableEvent(OrganizationContext.current().orElse(null), actorId, action, targetType, targetId,
                true, currentIp(), details, Instant.now());
    }

    public static AuditableEvent failure(UUID actorId, String action, String targetType, String targetId,
            Map<String, Object> details) {
        return new AuditableEvent(OrganizationContext.current().orElse(null), actorId, action, targetType, targetId,
                false, currentIp(), details, Instant.now());
    }

    /** IP người dùng: Nginx đặt X-Forwarded-For; lấy giá trị đầu tiên. */
    public static String currentIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest req = attrs.getRequest();
            String fwd = req.getHeader("X-Forwarded-For");
            if (fwd != null && !fwd.isBlank()) {
                return fwd.split(",")[0].trim();
            }
            return req.getRemoteAddr();
        }
        return null;
    }
}
