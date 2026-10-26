package vn.camhub.alerting.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import vn.camhub.access.api.AccessApi;
import vn.camhub.access.api.Permission;
import vn.camhub.alerting.api.AlertingApi;
import vn.camhub.alerting.domain.Alert;
import vn.camhub.alerting.domain.Alert.AlertType;
import vn.camhub.alerting.domain.Alert.Severity;
import vn.camhub.alerting.domain.AlertRule;
import vn.camhub.alerting.infrastructure.AlertRepository;
import vn.camhub.device.api.CameraChanged;
import vn.camhub.device.api.DeviceApi;
import vn.camhub.device.api.DeviceApi.CameraState;
import vn.camhub.notification.api.NotificationApi;
import vn.camhub.notification.api.NotificationApi.Channel;
import vn.camhub.organization.api.OrganizationApi;
import vn.camhub.platform.tenancy.OrganizationContext;

/**
 * Sinh cảnh báo hệ thống (UC-50):
 * <ul>
 *   <li>US-34: camera OFFLINE quá {@code offlineDelayMinutes} (mặc định 5) → CAMERA_OFFLINE. Mọi camera của một địa
 *       điểm (≥ 2 camera) cùng mất kết nối → chỉ 1 SITE_OFFLINE (gộp cấp địa điểm). Kết nối lại → RECOVERED.</li>
 *   <li>US-35: ổ lưu trữ vượt ngưỡng 85% (WARNING) / 95% (CRITICAL) → STORAGE_THRESHOLD kèm dung lượng còn lại và
 *       số ngày ước tính; xuống dưới ngưỡng → RECOVERED.</li>
 * </ul>
 * Người nhận: người có VIEW_LIVE trên camera/địa điểm (mất kết nối), MANAGE_SYSTEM toàn Tổ chức (lưu trữ).
 */
@Service
public class AlertingService implements AlertingApi {

    private static final Logger log = LoggerFactory.getLogger(AlertingService.class);

    private final AlertRepository repo;
    private final DeviceApi devices;
    private final OrganizationApi organizations;
    private final AccessApi access;
    private final NotificationApi notifications;
    private final TransactionTemplate tx;
    private final Clock clock;

    public AlertingService(AlertRepository repo, DeviceApi devices, OrganizationApi organizations, AccessApi access,
            NotificationApi notifications, TransactionTemplate tx, Clock clock) {
        this.repo = repo;
        this.devices = devices;
        this.organizations = organizations;
        this.access = access;
        this.notifications = notifications;
        this.tx = tx;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- mất kết nối (US-34)

    @Scheduled(fixedDelayString = "${camhub.alerting.offline-check-interval:30000}", initialDelay = 45_000)
    public void evaluateAll() {
        for (UUID org : repo.organizations()) {
            try {
                OrganizationContext.runAs(org, this::evaluateOffline);
            } catch (RuntimeException e) {
                log.warn("Không đánh giá được cảnh báo mất kết nối của Tổ chức {}: {}", org, e.toString());
            }
        }
    }

    /** Đánh giá mất kết nối cho Tổ chức hiện tại. */
    public void evaluateOffline() {
        AlertRule cameraRule = repo.rule(AlertType.CAMERA_OFFLINE);
        AlertRule siteRule = repo.rule(AlertType.SITE_OFFLINE);
        Instant now = clock.instant();
        List<CameraState> states = devices.states();
        Map<UUID, CameraState> byId = states.stream().collect(Collectors.toMap(CameraState::cameraId, s -> s));

        // Phục hồi: camera đã trực tuyến lại hoặc đã bị xóa
        for (Alert a : repo.open(AlertType.CAMERA_OFFLINE)) {
            CameraState s = byId.get(a.cameraId());
            if (s == null || !"OFFLINE".equals(s.status())) {
                recover(a, now);
            }
        }
        Map<UUID, List<CameraState>> bySite = states.stream()
                .collect(Collectors.groupingBy(CameraState::siteId, LinkedHashMap::new, Collectors.toList()));
        for (Alert a : repo.open(AlertType.SITE_OFFLINE)) {
            List<CameraState> cams = bySite.getOrDefault(a.siteId(), List.of());
            if (cams.stream().anyMatch(c -> !"OFFLINE".equals(c.status()))) {
                recover(a, now);
            }
        }

        for (Map.Entry<UUID, List<CameraState>> site : bySite.entrySet()) {
            List<CameraState> cams = site.getValue();
            Duration siteDelay = Duration.ofMinutes(siteRule.offlineDelayMinutes());
            boolean siteDown = siteRule.enabled() && cams.size() >= 2
                    && cams.stream().allMatch(c -> overdue(c, now, siteDelay));
            if (siteDown) {
                raiseSite(site.getKey(), cams, siteRule, now);
                continue; // gộp: không tạo cảnh báo riêng cho từng camera của địa điểm
            }
            if (!cameraRule.enabled()) {
                continue;
            }
            Duration delay = Duration.ofMinutes(cameraRule.offlineDelayMinutes());
            for (CameraState c : cams) {
                if (overdue(c, now, delay) && repo.openBySource(Alert.siteKey(site.getKey())).isEmpty()) {
                    raiseCamera(c, cameraRule);
                }
            }
        }
    }

    /** Camera trực tuyến lại thì phục hồi cảnh báo ngay, không đợi lượt đánh giá kế tiếp. */
    @EventListener
    public void onCameraChanged(CameraChanged e) {
        if (e.kind() != CameraChanged.Kind.ONLINE && e.kind() != CameraChanged.Kind.REMOVED) {
            return;
        }
        OrganizationContext.runAs(e.organizationId(), () -> repo.openBySource(Alert.cameraKey(e.cameraId()))
                .ifPresent(a -> recover(a, clock.instant())));
    }

    private static boolean overdue(CameraState c, Instant now, Duration delay) {
        return "OFFLINE".equals(c.status()) && c.statusSince() != null && !c.statusSince().plus(delay).isAfter(now);
    }

    private void raiseCamera(CameraState c, AlertRule rule) {
        String site = organizations.siteName(c.siteId()).orElse("");
        String title = "Camera " + c.name() + (site.isEmpty() ? "" : " (" + site + ")") + " mất kết nối";
        Map<String, Object> details = Map.of("cameraName", c.name(), "siteName", site,
                "offlineSince", c.statusSince().toString(), "delayMinutes", rule.offlineDelayMinutes());
        tx.executeWithoutResult(s -> repo.insertIfNoneOpen(AlertType.CAMERA_OFFLINE, Severity.WARNING,
                Alert.cameraKey(c.cameraId()), c.cameraId(), c.siteId(), title, details, c.statusSince())
                .ifPresent(id -> notify(id, title, "Mất kết nối từ " + c.statusSince(),
                        access.usersWithPermissionOnCamera(Permission.VIEW_LIVE, c.cameraId()), rule)));
    }

    private void raiseSite(UUID siteId, List<CameraState> cams, AlertRule rule, Instant now) {
        String site = organizations.siteName(siteId).orElse(siteId.toString());
        Instant since = cams.stream().map(CameraState::statusSince).min(Instant::compareTo).orElse(now);
        String title = "Địa điểm " + site + " mất kết nối (" + cams.size() + " camera)";
        Map<String, Object> details = Map.of("siteName", site, "cameraCount", cams.size(),
                "offlineSince", since.toString());
        List<UUID> ids = cams.stream().map(CameraState::cameraId).toList();
        tx.executeWithoutResult(s -> repo.insertIfNoneOpen(AlertType.SITE_OFFLINE, Severity.CRITICAL,
                Alert.siteKey(siteId), null, siteId, title, details, since)
                .ifPresent(id -> notify(id, title, cams.size() + " camera cùng mất kết nối từ " + since,
                        access.usersWithPermissionOnSite(Permission.VIEW_LIVE, siteId, ids), rule)));
    }

    // ---------------------------------------------------------------- lưu trữ (US-35)

    @Override
    public void onStorageMeasured(StorageReading r) {
        AlertRule rule = repo.rule(AlertType.STORAGE_THRESHOLD);
        String key = Alert.storageKey(r.volume());
        var open = repo.openBySource(key);
        Severity severity = !rule.enabled() ? null
                : r.usedPercent() >= rule.criticalPercent() ? Severity.CRITICAL
                : r.usedPercent() >= rule.warningPercent() ? Severity.WARNING : null;
        Instant now = clock.instant();
        if (severity == null) {
            open.ifPresent(a -> recover(a, now));
            return;
        }
        long freeGb = (r.totalBytes() - r.usedBytes()) / 1_073_741_824L;
        String days = r.daysLeft() == null ? "chưa ước tính được" : "ước tính " + r.daysLeft() + " ngày";
        String title = "Ổ lưu trữ " + r.volume() + " đã dùng " + r.usedPercent() + "% – còn " + freeGb + " GB, "
                + days;
        Map<String, Object> details = new HashMap<>();
        details.put("volume", r.volume());
        details.put("usedPercent", r.usedPercent());
        details.put("freeBytes", r.totalBytes() - r.usedBytes());
        details.put("totalBytes", r.totalBytes());
        details.put("daysLeft", r.daysLeft());
        details.put("threshold", severity == Severity.CRITICAL ? rule.criticalPercent() : rule.warningPercent());
        Set<UUID> admins = access.usersWithOrganizationPermission(Permission.MANAGE_SYSTEM);
        tx.executeWithoutResult(s -> {
            if (open.isPresent()) {
                // Chỉ nâng mức (WARNING → CRITICAL) và báo lại; cùng mức thì cập nhật số liệu, không báo lặp
                Alert a = open.get();
                repo.escalate(a.id(), severity.compareTo(a.severity()) > 0 ? severity : a.severity(), title, details);
                if (severity.compareTo(a.severity()) > 0) {
                    notify(a.id(), title, "Mức cảnh báo tăng lên " + severity, admins, rule);
                }
                return;
            }
            repo.insertIfNoneOpen(AlertType.STORAGE_THRESHOLD, severity, key, null, null, title, details, now)
                    .ifPresent(id -> notify(id, title, "Vượt ngưỡng " + details.get("threshold") + "%", admins,
                            rule));
        });
    }

    // ---------------------------------------------------------------- chung

    private void recover(Alert a, Instant at) {
        tx.executeWithoutResult(s -> {
            if (repo.recover(a.id(), at)) {
                log.info("Cảnh báo {} ({}) đã phục hồi", a.id(), a.type());
            }
        });
    }

    private void notify(UUID alertId, String title, String body, Collection<UUID> users, AlertRule rule) {
        Set<Channel> channels = EnumSet.of(Channel.WEB);
        if (rule.channels().contains("EMAIL")) {
            channels.add(Channel.EMAIL);
        }
        notifications.send(new ArrayList<>(users), new NotificationApi.Message(title, body, "/alerts?id=" + alertId,
                alertId), channels);
        log.info("Cảnh báo {}: {} → {} người nhận", alertId, title, users.size());
    }

    @Override
    public long openCount(UUID userId) {
        return repo.allOpen().stream().filter(a -> visible(userId, a)).count();
    }

    /** Quyền xem cảnh báo: theo camera/địa điểm nguồn; cảnh báo lưu trữ cho người quản trị hệ thống. */
    public boolean visible(UUID userId, Alert a) {
        return switch (a.type()) {
            case CAMERA_OFFLINE, MOTION -> a.cameraId() != null && access.canOnCamera(userId, Permission.VIEW_LIVE,
                    a.cameraId());
            case SITE_OFFLINE -> a.siteId() != null && access.canOnSite(userId, Permission.VIEW_LIVE, a.siteId());
            case STORAGE_THRESHOLD -> access.hasOrganizationWide(userId, Permission.MANAGE_SYSTEM);
        };
    }
}
