package vn.camhub.alerting.web;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.camhub.alerting.application.AlertWorkflowService;
import vn.camhub.alerting.application.AlertingService;
import vn.camhub.alerting.domain.MotionRule;
import vn.camhub.alerting.domain.Alert;
import vn.camhub.alerting.domain.Alert.AlertType;
import vn.camhub.alerting.domain.AlertRule;
import vn.camhub.alerting.infrastructure.AlertRepository;
import vn.camhub.platform.audit.AuditableEvent;
import vn.camhub.platform.security.ApiException;
import vn.camhub.platform.security.CurrentUser;

/** Cảnh báo (US-34.3 danh sách cơ bản) và quy tắc cảnh báo của Tổ chức (Admin). */
@RestController
public class AlertController {

    public record RuleBody(@NotNull Boolean enabled, Map<String, Object> params, List<String> channels) {
    }

    private final AlertingService alerting;
    private final AlertWorkflowService workflow;
    private final AlertRepository repo;
    private final org.springframework.context.ApplicationEventPublisher events;

    public AlertController(AlertingService alerting, AlertWorkflowService workflow, AlertRepository repo,
            org.springframework.context.ApplicationEventPublisher events) {
        this.alerting = alerting;
        this.workflow = workflow;
        this.repo = repo;
        this.events = events;
    }

    /** {@code status=OPEN} chỉ cảnh báo đang mở; mặc định mọi trạng thái trong 30 ngày. */
    @GetMapping("/api/v1/alerts")
    public List<Alert> list(@RequestParam(defaultValue = "ALL") String status,
            @RequestParam(required = false) AlertType type) {
        UUID me = me();
        return repo.recent("OPEN".equals(status), type, Instant.now().minus(Duration.ofDays(30)), 500).stream()
                .filter(a -> alerting.visible(me, a)).limit(200).toList();
    }

    @GetMapping("/api/v1/alerts/open-count")
    public Map<String, Long> openCount() {
        return Map.of("count", alerting.openCount(me()));
    }

    @GetMapping("/api/v1/alerts/{id}")
    public AlertWorkflowService.Detail get(@PathVariable UUID id) {
        return workflow.detail(me(), id);
    }

    // ---------------------------------------------------------------- xử lý, chuyển giao (US-37)

    public record ReassignBody(@NotNull UUID userId, String reason) {
    }

    public record CloseBody(String outcome, String note) {
    }

    @PostMapping("/api/v1/alerts/{id}/acknowledge")
    public AlertWorkflowService.Detail acknowledge(@PathVariable UUID id) {
        return workflow.acknowledge(me(), id);
    }

    @PostMapping("/api/v1/alerts/{id}/reassign")
    public AlertWorkflowService.Detail reassign(@PathVariable UUID id, @RequestBody ReassignBody body) {
        return workflow.reassign(me(), id, body.userId(), body.reason());
    }

    @PostMapping("/api/v1/alerts/{id}/close")
    public AlertWorkflowService.Detail close(@PathVariable UUID id, @RequestBody CloseBody body) {
        return workflow.close(me(), id, body.outcome(), body.note());
    }

    @GetMapping("/api/v1/alerts/{id}/assignees")
    public List<AlertWorkflowService.Person> assignees(@PathVariable UUID id) {
        return workflow.assignees(me(), id);
    }

    /** Ảnh sự kiện (US-36); trình duyệt tải bằng fetch có access token rồi hiển thị blob. */
    @GetMapping(value = "/api/v1/alerts/{id}/snapshot", produces = "image/jpeg")
    public ResponseEntity<byte[]> snapshot(@PathVariable UUID id) throws java.io.IOException {
        try (java.io.InputStream in = workflow.snapshot(me(), id)) {
            return ResponseEntity.ok().header("Cache-Control", "private, max-age=3600").body(in.readAllBytes());
        }
    }

    // ---------------------------------------------------------------- quy tắc chuyển động (US-36)

    @GetMapping("/api/v1/motion-rules")
    public List<MotionRule> motionRules() {
        return workflow.rules(me());
    }

    @PostMapping("/api/v1/motion-rules")
    public MotionRule createMotionRule(@RequestBody MotionRule body) {
        return workflow.saveRule(me(), new MotionRule(null, body.name(), body.enabled(), body.scopeType(),
                body.scopeId(), body.schedule(), body.cooldownSeconds(), body.channels(), body.includeSnapshot()));
    }

    @PutMapping("/api/v1/motion-rules/{id}")
    public MotionRule updateMotionRule(@PathVariable UUID id, @RequestBody MotionRule body) {
        return workflow.saveRule(me(), new MotionRule(id, body.name(), body.enabled(), body.scopeType(),
                body.scopeId(), body.schedule(), body.cooldownSeconds(), body.channels(), body.includeSnapshot()));
    }

    @DeleteMapping("/api/v1/motion-rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMotionRule(@PathVariable UUID id) {
        workflow.deleteRule(me(), id);
    }

    @GetMapping("/api/v1/alert-rules")
    @PreAuthorize("hasPermission(null, 'ORGANIZATION', 'MANAGE_SYSTEM')")
    public List<AlertRule> rules() {
        return Arrays.stream(AlertType.values()).filter(t -> t != AlertType.MOTION).map(repo::rule).toList();
    }

    @PutMapping("/api/v1/alert-rules/{type}")
    @PreAuthorize("hasPermission(null, 'ORGANIZATION', 'MANAGE_SYSTEM')")
    public AlertRule setRule(@PathVariable AlertType type, @RequestBody RuleBody body) {
        AlertRule current = repo.rule(type);
        AlertRule r = new AlertRule(type, body.enabled(), body.params() == null ? current.params() : body.params(),
                body.channels() == null ? current.channels() : new LinkedHashSet<>(body.channels()));
        if (!r.valid()) {
            throw ApiException.badRequest("INVALID_RULE", "Tham số quy tắc cảnh báo không hợp lệ");
        }
        repo.saveRule(r, me());
        events.publishEvent(AuditableEvent.of(me(), "CHANGE_CONFIG", "ALERT_RULE", type.name(),
                Map.of("params", r.params().toString(), "enabled", r.enabled())));
        return repo.rule(type);
    }

    private static UUID me() {
        return CurrentUser.require().userId();
    }
}
