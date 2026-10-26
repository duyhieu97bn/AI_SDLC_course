package vn.camhub.export.application;

import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import vn.camhub.access.api.AccessApi;
import vn.camhub.access.api.Permission;
import vn.camhub.device.api.DeviceApi;
import vn.camhub.device.api.DeviceApi.CameraMedia;
import vn.camhub.export.domain.ExportJob;
import vn.camhub.export.domain.ExportJob.Status;
import vn.camhub.export.infrastructure.ExportRepository;
import vn.camhub.notification.api.NotificationApi;
import vn.camhub.notification.api.NotificationApi.Channel;
import vn.camhub.platform.audit.AuditableEvent;
import vn.camhub.platform.media.MediaTokenService;
import vn.camhub.platform.messaging.MessagingTopology;
import vn.camhub.platform.outbox.Outbox;
import vn.camhub.platform.realtime.RealtimePublisher;
import vn.camhub.platform.security.ApiException;
import vn.camhub.platform.storage.ObjectStorage;
import vn.camhub.platform.tenancy.OrganizationContext;
import vn.camhub.recording.api.RecordingApi;

/**
 * Xuất clip (US-20, UC-33): kiểm tra quyền EXPORT_VIDEO và khoảng ≤ 60 phút, tạo ExportJob và phát
 * {@code export.requested} qua outbox cho Media Worker. Kết quả về qua {@link ExportResultListener}.
 * Liên kết tải ký 5 phút; clip hết hạn 24 giờ sau khi sẵn sàng.
 */
@Service
public class ExportService {

    public static final String BUCKET = "camhub-exports";
    static final Duration LINK_TTL = Duration.ofMinutes(5);
    static final int MAX_ACTIVE_PER_USER = 3;

    public record ExportView(UUID id, UUID cameraId, String cameraName, Instant startAt, Instant endAt, Status status,
            Instant requestedAt, Instant completedAt, Instant expiresAt, Long sizeBytes, String errorCode,
            String errorMessage) {

        static ExportView of(ExportJob j, Instant now) {
            Status s = j.expiredAt(now) ? Status.EXPIRED : j.status();
            return new ExportView(j.id(), j.cameraId(), j.cameraName(), j.startAt(), j.endAt(), s, j.requestedAt(),
                    j.completedAt(), j.expiresAt(), j.sizeBytes(), j.errorCode(), j.errorMessage());
        }
    }

    public record DownloadLink(String url, Instant expiresAt) {
    }

    /** Thông điệp cho Media Worker (hợp đồng với media-worker/media_worker/export_job.py). */
    public record ExportRequested(UUID exportId, UUID organizationId, UUID cameraId, String mediaPath, Instant start,
            long durationSeconds, String bucket, String objectKey) {
    }

    public record Download(String fileName, long size, InputStream content) {
    }

    private final ExportRepository repo;
    private final DeviceApi devices;
    private final AccessApi access;
    private final RecordingApi recordings;
    private final Outbox outbox;
    private final TransactionTemplate tx;
    private final MediaTokenService signer;
    private final ObjectStorage storage;
    private final RealtimePublisher realtime;
    private final NotificationApi notifications;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ExportService(ExportRepository repo, DeviceApi devices, AccessApi access, RecordingApi recordings,
            Outbox outbox, TransactionTemplate tx, MediaTokenService signer, ObjectStorage storage,
            RealtimePublisher realtime, NotificationApi notifications, ApplicationEventPublisher events, Clock clock) {
        this.repo = repo;
        this.devices = devices;
        this.access = access;
        this.recordings = recordings;
        this.outbox = outbox;
        this.tx = tx;
        this.signer = signer;
        this.storage = storage;
        this.realtime = realtime;
        this.notifications = notifications;
        this.events = events;
        this.clock = clock;
    }

    public ExportView request(UUID user, UUID cameraId, Instant start, Instant end) {
        if (!ExportJob.validRange(start, end)) {
            // US-20 AC2: yêu cầu rút ngắn, không tạo công việc
            throw ApiException.badRequest("EXPORT_RANGE_TOO_LONG", "Khoảng xuất tối đa 60 phút, vui lòng rút ngắn");
        }
        CameraMedia cam = devices.media(cameraId).orElseThrow(() -> ApiException.notFound("camera"));
        if (!access.canOnCamera(user, Permission.EXPORT_VIDEO, cameraId)) {
            throw ApiException.forbidden();
        }
        if (!recordings.hasRecording(cameraId, start, end)) {
            throw ApiException.badRequest("NO_RECORDING", "Không có video ghi trong khoảng đã chọn");
        }
        if (repo.activeCount(user) >= MAX_ACTIVE_PER_USER) {
            throw new ApiException(429, "TOO_MANY_EXPORTS", "Đang có " + MAX_ACTIVE_PER_USER
                    + " clip chờ xuất, vui lòng đợi hoàn tất");
        }
        UUID org = OrganizationContext.require();
        UUID id = UUID.randomUUID();
        String key = org + "/" + id + ".mp4";
        tx.executeWithoutResult(s -> {
            repo.insert(id, cameraId, cam.name(), start, end, user, BUCKET, key);
            outbox.append(MessagingTopology.JOBS, "export.requested", "ExportJob", id.toString(),
                    new ExportRequested(id, org, cameraId, cam.mediaPath(), start,
                            Duration.between(start, end).toSeconds(), BUCKET, key));
            events.publishEvent(AuditableEvent.of(user, "EXPORT_VIDEO", "CAMERA", cameraId.toString(),
                    Map.of("targetName", cam.name(), "from", start.toString(), "to", end.toString(),
                            "exportId", id.toString())));
        });
        return view(repo.find(id).orElseThrow());
    }

    public List<ExportView> mine(UUID user) {
        Instant now = clock.instant();
        return repo.byUser(user, now.minus(Duration.ofDays(7))).stream().map(j -> ExportView.of(j, now)).toList();
    }

    /** Liên kết tải ký 5 phút (US-20.3). Clip quá 24 giờ thì "Hết hạn", phải xuất lại (AC4). */
    public DownloadLink link(UUID user, UUID id) {
        ExportJob j = own(user, id);
        Instant now = clock.instant();
        if (j.expiredAt(now)) {
            throw new ApiException(410, "EXPORT_EXPIRED", "Clip đã hết hạn, vui lòng xuất lại");
        }
        if (j.status() != Status.READY) {
            throw new ApiException(409, "EXPORT_NOT_READY", "Clip chưa sẵn sàng");
        }
        long exp = now.plus(LINK_TTL).getEpochSecond();
        UUID org = OrganizationContext.require();
        String sig = signer.signLink(resource(org, id), exp);
        return new DownloadLink("/api/v1/exports/" + id + "/file?org=" + org + "&exp=" + exp + "&sig=" + sig,
                Instant.ofEpochSecond(exp));
    }

    /** Mở tệp theo liên kết đã ký (không cần access token: trình duyệt tải trực tiếp). */
    public Download open(UUID org, UUID id, long exp, String sig) {
        if (!signer.verifyLink(resource(org, id), exp, sig)) {
            throw new ApiException(403, "LINK_INVALID", "Liên kết tải không hợp lệ hoặc đã hết hạn");
        }
        return OrganizationContext.callAs(org, () -> {
            ExportJob j = repo.find(id).orElseThrow(() -> ApiException.notFound("export"));
            if (j.expiredAt(clock.instant()) || j.status() != Status.READY) {
                throw new ApiException(410, "EXPORT_EXPIRED", "Clip đã hết hạn, vui lòng xuất lại");
            }
            return new Download(fileName(j), j.sizeBytes() == null ? -1 : j.sizeBytes(),
                    storage.open(j.bucket(), j.objectKey()));
        });
    }

    // ---------------------------------------------------------------- kết quả từ Media Worker

    void onProgress(UUID id) {
        if (Boolean.TRUE.equals(tx.execute(s -> repo.transition(id, Status.QUEUED, Status.PROCESSING)))) {
            push(id);
        }
    }

    void onCompleted(UUID id, long size, String sha256) {
        Instant now = clock.instant();
        ExportJob j = repo.find(id).orElse(null);
        if (j == null || !j.status().canMoveTo(Status.READY)) {
            return;
        }
        Boolean done = tx.execute(s -> {
            if (!repo.markReady(id, j.status(), now, now.plus(ExportJob.TIME_TO_LIVE), size, sha256)) {
                return false;
            }
            notifications.send(List.of(j.requestedBy()), new NotificationApi.Message(
                    "Clip đã sẵn sàng: " + j.cameraName(), "Clip " + j.startAt() + " – " + j.endAt()
                            + " đã sẵn sàng để tải trong 24 giờ.", "/exports", null), Set.of(Channel.WEB));
            return true;
        });
        if (Boolean.TRUE.equals(done)) {
            push(id);
        }
    }

    void onFailed(UUID id, String code, String message) {
        ExportJob j = repo.find(id).orElse(null);
        if (j == null || !j.status().canMoveTo(Status.FAILED)) {
            return;
        }
        Boolean done = tx.execute(s -> {
            if (!repo.markFailed(id, j.status(), clock.instant(), code == null ? "INTERNAL" : code, message)) {
                return false;
            }
            notifications.send(List.of(j.requestedBy()), new NotificationApi.Message(
                    "Xuất clip thất bại: " + j.cameraName(), message, "/exports", null), Set.of(Channel.WEB));
            return true;
        });
        if (Boolean.TRUE.equals(done)) {
            push(id);
        }
    }

    // ---------------------------------------------------------------- hết hạn (US-20.3)

    /** Clip quá 24 giờ: chuyển EXPIRED và xóa tệp; việc treo quá 2 giờ thì FAILED. Trả về số clip đã xử lý. */
    int expireOrganization() {
        Instant now = clock.instant();
        int n = 0;
        for (ExportJob j : repo.readyExpiredBefore(now)) {
            try {
                storage.delete(j.bucket(), j.objectKey());
            } catch (ObjectStorage.StorageException e) {
                continue; // thử lại lần chạy sau, giữ READY để không mất dấu tệp
            }
            if (Boolean.TRUE.equals(tx.execute(s -> repo.transition(j.id(), Status.READY, Status.EXPIRED)))) {
                n++;
            }
        }
        for (ExportJob j : repo.stuckBefore(now.minus(Duration.ofHours(2)))) {
            tx.executeWithoutResult(s -> repo.markFailed(j.id(), j.status(), now, "TIMEOUT",
                    "Media Worker không phản hồi"));
            n++;
        }
        return n;
    }

    private void push(UUID id) {
        repo.find(id).ifPresent(j -> realtime.toUser(j.requestedBy(), "exports", view(j)));
    }

    private ExportJob own(UUID user, UUID id) {
        ExportJob j = repo.find(id).orElseThrow(() -> ApiException.notFound("export"));
        if (!j.requestedBy().equals(user) && !access.hasOrganizationWide(user, Permission.MANAGE_SYSTEM)) {
            throw ApiException.notFound("export");
        }
        return j;
    }

    private ExportView view(ExportJob j) {
        return ExportView.of(j, clock.instant());
    }

    private static String resource(UUID org, UUID id) {
        return "export:" + org + ":" + id;
    }

    static String fileName(ExportJob j) {
        String safe = j.cameraName().replaceAll("[^\\p{L}\\p{N}_-]+", "_");
        return safe + "_" + j.startAt().toString().replace(":", "").replace("-", "") + ".mp4";
    }
}
