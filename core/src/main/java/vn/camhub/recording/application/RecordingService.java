package vn.camhub.recording.application;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
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
import vn.camhub.organization.api.OrganizationApi;
import vn.camhub.platform.audit.AuditableEvent;
import vn.camhub.platform.security.ApiException;
import vn.camhub.platform.tenancy.OrganizationContext;
import vn.camhub.recording.domain.RecordingPolicy;
import vn.camhub.recording.domain.RecordingPolicy.Mode;
import vn.camhub.recording.domain.SegmentFile;
import vn.camhub.recording.infrastructure.RecordingRepository;
import vn.camhub.recording.infrastructure.RecordingRepository.Segment;

/**
 * Chế độ ghi hình (US-16) và chỉ mục đoạn ghi (US-17.1).
 * <ul>
 *   <li>Liên tục / theo sự kiện: MediaMTX ghi liên tục; theo sự kiện thì job dọn chỉ giữ đoạn quanh mốc chuyển động
 *       (trước 10 giây, sau 30 giây mặc định) – cách duy nhất có video <i>trước</i> sự kiện.</li>
 *   <li>Theo lịch tuần: mỗi phút đánh giá lịch theo múi giờ địa điểm, bật/tắt ghi trên MediaMTX.</li>
 *   <li>Hook {@code runOnRecordSegmentComplete} của MediaMTX → bảng recording_segment.</li>
 * </ul>
 */
@Service
public class RecordingService {

    public record BulkResult(UUID cameraId, boolean ok, String code, String message) {
    }

    private static final Logger log = LoggerFactory.getLogger(RecordingService.class);
    static final Duration EVENT_GRACE = Duration.ofMinutes(5);

    private final RecordingRepository repo;
    private final DeviceApi devices;
    private final AccessApi access;
    private final OrganizationApi organizations;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public RecordingService(RecordingRepository repo, DeviceApi devices, AccessApi access,
            OrganizationApi organizations, TransactionTemplate tx, ApplicationEventPublisher events, Clock clock) {
        this.repo = repo;
        this.devices = devices;
        this.access = access;
        this.organizations = organizations;
        this.tx = tx;
        this.events = events;
        this.clock = clock;
    }

    public RecordingPolicy policy(UUID actor, UUID cameraId) {
        requireAny(actor, cameraId, Permission.CONFIGURE_CAMERA, Permission.VIEW_PLAYBACK, Permission.VIEW_LIVE);
        return repo.policy(cameraId).orElse(RecordingPolicy.off(cameraId));
    }

    public RecordingPolicy setPolicy(UUID actor, RecordingPolicy p) {
        requireAny(actor, p.cameraId(), Permission.CONFIGURE_CAMERA);
        for (var s : p.schedule()) {
            String err = s.validate();
            if (err != null) {
                throw ApiException.badRequest("INVALID_SCHEDULE", err);
            }
        }
        if (p.mode() == Mode.SCHEDULED && p.schedule().isEmpty()) {
            throw ApiException.badRequest("INVALID_SCHEDULE", "Chọn ít nhất một khung giờ ghi");
        }
        tx.executeWithoutResult(s -> {
            repo.savePolicy(p, actor);
            events.publishEvent(AuditableEvent.of(actor, "CHANGE_CONFIG", "CAMERA", p.cameraId().toString(),
                    Map.of("operation", "RECORDING_POLICY", "mode", p.mode().name(),
                            "preEventSeconds", p.preEventSeconds(), "postEventSeconds", p.postEventSeconds())));
        });
        apply(p);
        return p;
    }

    /** Áp một chế độ cho nhiều camera (US-16 AC2); kết quả riêng từng camera. */
    public List<BulkResult> setPolicies(UUID actor, List<UUID> cameraIds, RecordingPolicy template) {
        List<BulkResult> out = new ArrayList<>();
        for (UUID id : cameraIds) {
            try {
                setPolicy(actor, new RecordingPolicy(id, template.mode(), template.schedule(),
                        template.preEventSeconds(), template.postEventSeconds()));
                out.add(new BulkResult(id, true, null, null));
            } catch (ApiException e) {
                out.add(new BulkResult(id, false, e.code(), e.getMessage()));
            }
        }
        return out;
    }

    /** Bật/tắt ghi trên MediaMTX theo chính sách tại thời điểm hiện tại. */
    void apply(RecordingPolicy p) {
        devices.media(p.cameraId()).ifPresent(m -> {
            ZoneId tz = ZoneId.of(organizations.siteTimezone(m.siteId()));
            boolean want = p.shouldRecord(clock.instant().atZone(tz));
            if (want != m.recording()) {
                devices.setRecording(p.cameraId(), want);
                log.info("Camera {} {} ghi hình (chế độ {})", p.cameraId(), want ? "bắt đầu" : "dừng", p.mode());
            }
        });
    }

    /** Mỗi phút: đánh giá lịch tuần; đồng thời sửa lệch nếu MediaMTX/DB bị đổi ngoài ý muốn. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 20_000)
    public void evaluateSchedules() {
        for (UUID org : repo.organizations()) {
            OrganizationContext.runAs(org, () -> repo.policies().forEach(p -> {
                try {
                    apply(p);
                } catch (RuntimeException e) {
                    log.warn("Không áp được chính sách ghi cho camera {}: {}", p.cameraId(), e.toString());
                }
            }));
        }
    }

    /** Hook từ MediaMTX sau mỗi đoạn 60 giây. Chỉ ghi chỉ mục cho luồng chính của camera. */
    /** Độ dài đoạn ghi cấu hình trong MediaMTX ({@code recordSegmentDuration}) và mức tối đa chấp nhận. */
    static final Duration NOMINAL_SEGMENT = Duration.ofSeconds(60);
    static final Duration MAX_SEGMENT = Duration.ofMinutes(3);

    public void indexSegment(String path, String file, String duration) {
        if (path == null || path.endsWith("-sub") || path.contains("_h264") || path.startsWith("test-")) {
            return;
        }
        var owner = devices.cameraOfPath(path).orElse(null);
        if (owner == null) {
            log.debug("Đoạn ghi của path không thuộc camera nào: {}", path);
            return;
        }
        Instant start = SegmentFile.startOf(file).orElse(null);
        if (start == null) {
            log.warn("Không đọc được thời điểm bắt đầu từ tên file {}", file);
            return;
        }
        Duration d = SegmentFile.parseGoDuration(duration);
        // Hook có thể báo thời lượng 0/sai (MediaMTX bị dừng giữa chừng): không lấy "bây giờ" làm điểm kết thúc,
        // nếu không timeline sẽ có khoảng ghi giả mà máy chủ playback không phát được
        if (d.isZero() || d.isNegative() || d.compareTo(MAX_SEGMENT) > 0) {
            log.warn("Đoạn ghi {} báo thời lượng bất thường '{}', dùng {} giây", file, duration,
                    NOMINAL_SEGMENT.toSeconds());
            d = NOMINAL_SEGMENT;
        }
        Instant end = start.plus(d);
        long size = sizeOf(file);
        OrganizationContext.runAs(owner.organizationId(),
                () -> tx.executeWithoutResult(s -> repo.insertSegment(owner.cameraId(), start, end, file, size)));
    }

    /** Ghi theo sự kiện: xóa đoạn đã qua 5 phút mà không giao với cửa sổ sự kiện nào và không bị giữ lại. */
    @Scheduled(fixedDelay = 120_000, initialDelay = 60_000)
    public int cleanEventModeRecordings() {
        int removed = 0;
        for (UUID org : repo.organizations()) {
            removed += OrganizationContext.callAs(org, () -> {
                int n = 0;
                for (RecordingPolicy p : repo.policies()) {
                    if (p.mode() != Mode.EVENT) {
                        continue;
                    }
                    List<Segment> drop = repo.eventModeDiscardable(p.cameraId(), clock.instant().minus(EVENT_GRACE),
                            p.preEventSeconds(), p.postEventSeconds(), 500);
                    RetentionService.deleteFiles(drop);
                    tx.executeWithoutResult(s -> repo.deleteSegments(drop));
                    n += drop.size();
                }
                return n;
            });
        }
        return removed;
    }

    /** Mốc chuyển động (từ sự kiện camera) cho ghi theo sự kiện và timeline. */
    public void markMotion(UUID cameraId, Instant at) {
        devices.organizationOf(cameraId).ifPresent(org -> OrganizationContext.runAs(org,
                () -> tx.executeWithoutResult(s -> repo.insertEvent(cameraId, at))));
    }

    @Scheduled(cron = "0 0 3 1 * *")
    public void ensurePartitions() {
        repo.ensurePartitions();
    }

    private void requireAny(UUID actor, UUID cameraId, Permission... perms) {
        devices.media(cameraId).orElseThrow(() -> ApiException.notFound("camera"));
        for (Permission p : perms) {
            if (access.canOnCamera(actor, p, cameraId)) {
                return;
            }
        }
        throw ApiException.forbidden();
    }

    private static long sizeOf(String file) {
        try {
            return Files.size(Path.of(file));
        } catch (Exception e) {
            return 0;
        }
    }
}
