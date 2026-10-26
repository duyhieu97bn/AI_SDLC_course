package vn.camhub.alerting.application;

import java.io.InputStream;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import vn.camhub.access.api.AccessApi;
import vn.camhub.access.api.Permission;
import vn.camhub.alerting.domain.Alert;
import vn.camhub.alerting.domain.Alert.Outcome;
import vn.camhub.alerting.domain.Alert.Status;
import vn.camhub.alerting.domain.MotionRule;
import vn.camhub.alerting.infrastructure.AlertRepository;
import vn.camhub.alerting.infrastructure.AlertRepository.StatusChange;
import vn.camhub.alerting.infrastructure.MotionRuleRepository;
import vn.camhub.device.api.DeviceApi;
import vn.camhub.identity.api.IdentityApi;
import vn.camhub.notification.api.NotificationApi;
import vn.camhub.notification.api.NotificationApi.Channel;
import vn.camhub.platform.audit.AuditableEvent;
import vn.camhub.platform.security.ApiException;
import vn.camhub.platform.storage.ObjectStorage;

/**
 * Xử lý và chuyển giao cảnh báo (US-37, UC-52/53): nhận xử lý (Mới → Đang xử lý, phụ trách là người nhận), chuyển giao
 * kèm lý do cho người cũng thấy được cảnh báo (INV-28) và báo cho người nhận, đóng bắt buộc chọn kết quả (INV-14).
 * Quản lý quy tắc chuyển động (US-36).
 */
@Service
public class AlertWorkflowService {

    public record Person(UUID id, String name) {
    }

    public record Detail(Alert alert, List<StatusChange> history, List<HandoverView> handovers, Person assignee,
            boolean canAct) {
    }

    public record HandoverView(Person from, Person to, String reason, java.time.Instant at) {
    }

    private final AlertRepository alerts;
    private final MotionRuleRepository rules;
    private final AlertingService alerting;
    private final AccessApi access;
    private final IdentityApi identity;
    private final DeviceApi devices;
    private final NotificationApi notifications;
    private final ObjectStorage storage;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;

    public AlertWorkflowService(AlertRepository alerts, MotionRuleRepository rules, AlertingService alerting,
            AccessApi access, IdentityApi identity, DeviceApi devices, NotificationApi notifications,
            ObjectStorage storage, TransactionTemplate tx, ApplicationEventPublisher events) {
        this.alerts = alerts;
        this.rules = rules;
        this.alerting = alerting;
        this.access = access;
        this.identity = identity;
        this.devices = devices;
        this.notifications = notifications;
        this.storage = storage;
        this.tx = tx;
        this.events = events;
    }

    public Detail detail(UUID actor, UUID id) {
        Alert a = visible(actor, id);
        List<HandoverView> handovers = alerts.handovers(id).stream()
                .map(h -> new HandoverView(person(h.fromUser()), person(h.toUser()), h.reason(), h.at())).toList();
        return new Detail(a, alerts.history(id), handovers, a.assigneeId() == null ? null : person(a.assigneeId()),
                a.status() != Status.CLOSED);
    }

    /** AC1: Mới → Đang xử lý, phụ trách là người bấm. */
    public Detail acknowledge(UUID actor, UUID id) {
        Alert a = visible(actor, id);
        if (a.status() != Status.NEW) {
            throw new ApiException(409, "ALERT_NOT_NEW", "Cảnh báo đã có người nhận xử lý");
        }
        tx.executeWithoutResult(s -> {
            if (!alerts.transition(id, Status.NEW, Status.IN_PROGRESS, actor, actor, null, null)) {
                throw new ApiException(409, "ALERT_NOT_NEW", "Cảnh báo đã có người nhận xử lý");
            }
            audit(actor, a, "ACKNOWLEDGE_ALERT", Map.of());
        });
        return detail(actor, id);
    }

    /** AC2: chuyển giao cho người khác kèm lý do; người nhận phải thấy được cảnh báo (INV-28) và được thông báo. */
    public Detail reassign(UUID actor, UUID id, UUID to, String reason) {
        Alert a = visible(actor, id);
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("REASON_REQUIRED", "Nhập lý do chuyển giao");
        }
        if (a.status() != Status.IN_PROGRESS || !actor.equals(a.assigneeId())) {
            throw new ApiException(409, "NOT_ASSIGNEE", "Chỉ người đang xử lý mới chuyển giao được");
        }
        if (to.equals(actor) || !alerting.visible(to, a)) {
            throw ApiException.badRequest("INVALID_ASSIGNEE", "Người nhận không có quyền với cảnh báo này");
        }
        tx.executeWithoutResult(s -> {
            if (!alerts.reassign(id, actor, to, reason.trim())) {
                throw new ApiException(409, "NOT_ASSIGNEE", "Cảnh báo đã thay đổi, tải lại để xem");
            }
            audit(actor, a, "REASSIGN_ALERT", Map.of("to", to.toString(), "reason", reason.trim()));
            notifications.send(List.of(to), new NotificationApi.Message("Bạn được giao xử lý: " + a.title(),
                    identity.displayName(actor).orElse("") + " chuyển giao: " + reason.trim(), "/alerts?id=" + id, id),
                    EnumSet.of(Channel.WEB, Channel.EMAIL, Channel.TELEGRAM));
        });
        return detail(actor, id);
    }

    /** AC3: đóng bắt buộc chọn kết quả. */
    public Detail close(UUID actor, UUID id, String outcome, String note) {
        Alert a = visible(actor, id);
        Outcome o;
        try {
            o = outcome == null ? null : Outcome.valueOf(outcome);
        } catch (IllegalArgumentException e) {
            o = null;
        }
        if (o == null) {
            throw ApiException.badRequest("OUTCOME_REQUIRED", "Chọn kết quả xử lý trước khi đóng");
        }
        if (a.status() == Status.CLOSED) {
            throw new ApiException(409, "ALERT_CLOSED", "Cảnh báo đã đóng");
        }
        if (a.status() == Status.IN_PROGRESS && !actor.equals(a.assigneeId())
                && !access.hasOrganizationWide(actor, Permission.MANAGE_SYSTEM)) {
            throw new ApiException(409, "NOT_ASSIGNEE", "Chỉ người đang xử lý mới đóng được");
        }
        Outcome chosen = o;
        tx.executeWithoutResult(s -> {
            String trimmed = note == null || note.isBlank() ? null : note.trim();
            if (!alerts.transition(id, a.status(), Status.CLOSED, actor, a.assigneeId() == null ? actor : null,
                    chosen.name(), trimmed)) {
                throw new ApiException(409, "ALERT_CHANGED", "Cảnh báo đã thay đổi, tải lại để xem");
            }
            audit(actor, a, "CLOSE_ALERT", Map.of("outcome", chosen.name()));
        });
        return detail(actor, id);
    }

    /** Người có thể nhận chuyển giao: thấy được cảnh báo (INV-28), trừ người đang xử lý. */
    public List<Person> assignees(UUID actor, UUID id) {
        Alert a = visible(actor, id);
        Set<UUID> users = a.cameraId() != null
                ? access.usersWithPermissionOnCamera(Permission.VIEW_LIVE, a.cameraId())
                : access.usersWithOrganizationPermission(Permission.MANAGE_SYSTEM);
        return users.stream().filter(u -> !u.equals(actor)).filter(u -> alerting.visible(u, a)).map(this::person)
                .sorted(Comparator.comparing(Person::name)).toList();
    }

    public InputStream snapshot(UUID actor, UUID id) {
        visible(actor, id);
        String[] s = alerts.snapshot(id).orElseThrow(() -> ApiException.notFound("snapshot"));
        return storage.open(s[0], s[1]);
    }

    // ---------------------------------------------------------------- quy tắc chuyển động (US-36)

    public List<MotionRule> rules(UUID actor) {
        requireAdmin(actor);
        return rules.all();
    }

    public MotionRule saveRule(UUID actor, MotionRule r) {
        requireAdmin(actor);
        if (r.name() == null || r.name().isBlank() || r.scopeType() == null
                || (r.scopeType() != MotionRule.ScopeType.ORGANIZATION && r.scopeId() == null)
                || r.cooldownSeconds() < 0 || r.cooldownSeconds() > 86_400
                || (r.schedule() != null && r.schedule().stream().anyMatch(sl -> !sl.valid()))) {
            throw ApiException.badRequest("INVALID_RULE", "Quy tắc chuyển động không hợp lệ");
        }
        // AC4: camera không phát hiện chuyển động thì không tạo được quy tắc cho riêng camera đó
        if (r.scopeType() == MotionRule.ScopeType.CAMERA && !devices.state(r.scopeId())
                .map(DeviceApi.CameraState::motionDetection).orElse(false)) {
            throw ApiException.badRequest("MOTION_NOT_SUPPORTED", "Camera không có chức năng phát hiện chuyển động");
        }
        MotionRule saved = new MotionRule(r.id() == null ? UUID.randomUUID() : r.id(), r.name().trim(), r.enabled(),
                r.scopeType(), r.scopeType() == MotionRule.ScopeType.ORGANIZATION ? null : r.scopeId(),
                r.schedule() == null ? List.of() : r.schedule(), r.cooldownSeconds(),
                r.channels() == null || r.channels().isEmpty() ? Set.of("WEB") : r.channels(), r.includeSnapshot());
        tx.executeWithoutResult(s -> {
            rules.save(saved, actor);
            events.publishEvent(AuditableEvent.of(actor, "CHANGE_CONFIG", "MOTION_RULE", saved.id().toString(),
                    Map.of("targetName", saved.name(), "scope", saved.scopeType().name())));
        });
        return rules.find(saved.id()).orElseThrow();
    }

    public void deleteRule(UUID actor, UUID id) {
        requireAdmin(actor);
        tx.executeWithoutResult(s -> {
            if (!rules.delete(id)) {
                throw ApiException.notFound("rule");
            }
            events.publishEvent(AuditableEvent.of(actor, "CHANGE_CONFIG", "MOTION_RULE", id.toString(),
                    Map.of("operation", "DELETE")));
        });
    }

    // ---------------------------------------------------------------- chung

    private Alert visible(UUID actor, UUID id) {
        return alerts.find(id).filter(a -> alerting.visible(actor, a))
                .orElseThrow(() -> ApiException.notFound("alert"));
    }

    private Person person(UUID id) {
        return new Person(id, identity.displayName(id).orElse(id.toString()));
    }

    private void requireAdmin(UUID actor) {
        if (!access.hasOrganizationWide(actor, Permission.MANAGE_SYSTEM)) {
            throw ApiException.forbidden();
        }
    }

    private void audit(UUID actor, Alert a, String action, Map<String, Object> extra) {
        Map<String, Object> d = new java.util.HashMap<>(extra);
        d.put("targetName", a.title());
        events.publishEvent(AuditableEvent.of(actor, action, "ALERT", a.id().toString(), d));
    }
}
