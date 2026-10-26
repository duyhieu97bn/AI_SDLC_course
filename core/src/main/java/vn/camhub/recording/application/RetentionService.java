package vn.camhub.recording.application;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import vn.camhub.access.api.AccessApi;
import vn.camhub.access.api.Permission;
import vn.camhub.device.api.DeviceApi;
import vn.camhub.platform.audit.AuditableEvent;
import vn.camhub.platform.security.ApiException;
import vn.camhub.platform.tenancy.OrganizationContext;
import vn.camhub.recording.infrastructure.RecordingRepository;
import vn.camhub.recording.infrastructure.RecordingRepository.Hold;
import vn.camhub.recording.infrastructure.RecordingRepository.Segment;

/**
 * Thời gian lưu trữ và giữ lại phục vụ điều tra (US-21, UC-35, UC-36, SADD 3.11.2):
 * mặc định theo Tổ chức, ghi đè theo camera; job 02:00 hằng ngày xóa đoạn quá hạn theo lô 500, bỏ qua đoạn nằm trong
 * khoảng giữ lại chưa giải phóng (INV-07). Chỉ người có HOLD_EVIDENCE mới đánh dấu/bỏ giữ lại (RULE-02).
 */
@Service
public class RetentionService {

    public record PurgeResult(int segments, long bytes) {
    }

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);
    static final int BATCH = 500;

    private final RecordingRepository repo;
    private final DeviceApi devices;
    private final AccessApi access;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public RetentionService(RecordingRepository repo, DeviceApi devices, AccessApi access, TransactionTemplate tx,
            ApplicationEventPublisher events, Clock clock) {
        this.repo = repo;
        this.devices = devices;
        this.access = access;
        this.tx = tx;
        this.events = events;
        this.clock = clock;
    }

    public int defaultDays() {
        return repo.defaultRetentionDays();
    }

    public void setDefaultDays(UUID actor, int days) {
        if (!access.hasOrganizationWide(actor, Permission.MANAGE_SYSTEM)) {
            throw ApiException.forbidden();
        }
        tx.executeWithoutResult(s -> {
            repo.setDefaultRetentionDays(days, actor);
            events.publishEvent(AuditableEvent.of(actor, "CHANGE_CONFIG", "RETENTION_POLICY", null,
                    Map.of("defaultRetentionDays", days)));
        });
    }

    public Integer overrideDays(UUID actor, UUID cameraId) {
        requireCamera(actor, cameraId, Permission.VIEW_PLAYBACK, Permission.CONFIGURE_CAMERA);
        return repo.overrideDays(cameraId).orElse(null);
    }

    public void setOverride(UUID actor, UUID cameraId, Integer days) {
        requireCamera(actor, cameraId, Permission.CONFIGURE_CAMERA);
        tx.executeWithoutResult(s -> {
            repo.setOverride(cameraId, days, actor);
            events.publishEvent(AuditableEvent.of(actor, "CHANGE_CONFIG", "CAMERA", cameraId.toString(),
                    Map.of("operation", "RETENTION_OVERRIDE", "retentionDays", String.valueOf(days))));
        });
    }

    public List<Hold> holds(UUID actor, UUID cameraId) {
        requireCamera(actor, cameraId, Permission.VIEW_PLAYBACK, Permission.HOLD_EVIDENCE);
        return repo.holds(cameraId);
    }

    public UUID hold(UUID actor, UUID cameraId, Instant start, Instant end, String reason) {
        requireCamera(actor, cameraId, Permission.HOLD_EVIDENCE);
        if (!end.isAfter(start)) {
            throw ApiException.badRequest("INVALID_RANGE", "Thời điểm kết thúc phải sau thời điểm bắt đầu");
        }
        return tx.execute(s -> {
            UUID id = repo.insertHold(cameraId, start, end, reason, actor);
            events.publishEvent(AuditableEvent.of(actor, "HOLD_EVIDENCE", "CAMERA", cameraId.toString(),
                    Map.of("holdId", id.toString(), "from", start.toString(), "to", end.toString(), "reason", reason)));
            return id;
        });
    }

    public void release(UUID actor, UUID holdId) {
        Hold h = repo.hold(holdId).orElseThrow(() -> ApiException.notFound("khoảng giữ lại"));
        requireCamera(actor, h.cameraId(), Permission.HOLD_EVIDENCE);
        tx.executeWithoutResult(s -> {
            repo.releaseHold(holdId, actor);
            events.publishEvent(AuditableEvent.of(actor, "RELEASE_EVIDENCE", "CAMERA", h.cameraId().toString(),
                    Map.of("holdId", holdId.toString())));
        });
    }

    /** Job 02:00 hằng ngày cho mọi Tổ chức (SADD 3.11.2). */
    @Scheduled(cron = "${camhub.recording.purge-cron:0 0 2 * * *}")
    public void purgeAll() {
        for (UUID org : repo.organizations()) {
            PurgeResult r = OrganizationContext.callAs(org, this::purgeOrganization);
            if (r.segments() > 0) {
                log.info("Đã xóa {} đoạn ghi quá hạn ({} MB) của Tổ chức {}", r.segments(), r.bytes() / 1_048_576, org);
            }
        }
    }

    /** INV-06: hết hạn khi end_at + số ngày lưu < hiện tại; số ngày lấy từ ghi đè theo camera nếu có. */
    public PurgeResult purgeOrganization() {
        int defaultDays = repo.defaultRetentionDays();
        int total = 0;
        long bytes = 0;
        for (UUID camera : repo.camerasWithSegments()) {
            int days = repo.overrideDays(camera).orElse(defaultDays);
            Instant cutoff = clock.instant().minus(Duration.ofDays(days));
            List<Segment> batch;
            do {
                batch = repo.expired(camera, cutoff, BATCH);
                List<Segment> b = batch;
                deleteFiles(b);
                tx.executeWithoutResult(s -> repo.deleteSegments(b));
                total += b.size();
                bytes += b.stream().mapToLong(Segment::sizeBytes).sum();
            } while (batch.size() == BATCH);
        }
        return new PurgeResult(total, bytes);
    }

    /** Xóa tệp trước rồi mới xóa bản ghi; tệp đã mất thì bỏ qua (chạy lại không lỗi). */
    static void deleteFiles(List<Segment> segs) {
        for (Segment s : segs) {
            try {
                Files.deleteIfExists(Path.of(s.location()));
            } catch (Exception e) {
                log.warn("Không xóa được tệp {}: {}", s.location(), e.toString());
            }
        }
    }

    private void requireCamera(UUID actor, UUID cameraId, Permission... any) {
        devices.media(cameraId).orElseThrow(() -> ApiException.notFound("camera"));
        for (Permission p : any) {
            if (access.canOnCamera(actor, p, cameraId)) {
                return;
            }
        }
        throw ApiException.forbidden();
    }
}
