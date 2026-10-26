package vn.camhub.recording.application;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vn.camhub.alerting.api.AlertingApi;
import vn.camhub.alerting.api.AlertingApi.StorageReading;
import vn.camhub.platform.tenancy.OrganizationContext;
import vn.camhub.recording.api.RecordingApi.StorageVolume;
import vn.camhub.recording.infrastructure.RecordingRepository;
import vn.camhub.recording.infrastructure.StorageRepository;
import vn.camhub.recording.infrastructure.StorageRepository.Volume;

/**
 * Đo dung lượng ổ ghi hình mỗi 5 phút (US-35): lưu số đo, ước tính số ngày trước khi đầy theo tốc độ tăng 24 giờ,
 * rồi báo cho module alerting của từng Tổ chức để so với ngưỡng (mặc định 85% và 95%).
 */
@Component
public class StorageMonitorJob {

    private static final Logger log = LoggerFactory.getLogger(StorageMonitorJob.class);
    static final String VOLUME = "recordings";

    private final StorageRepository storage;
    private final RecordingRepository recordings;
    private final AlertingApi alerting;
    private final Clock clock;
    private final Path root;

    public StorageMonitorJob(StorageRepository storage, RecordingRepository recordings, AlertingApi alerting,
            Clock clock, @Value("${camhub.recording.root:/recordings}") String root) {
        this.storage = storage;
        this.recordings = recordings;
        this.alerting = alerting;
        this.clock = clock;
        this.root = Path.of(root);
    }

    @Scheduled(fixedDelayString = "${camhub.recording.storage-check-interval:300000}", initialDelay = 30_000)
    public void measure() {
        if (!Files.isDirectory(root)) {
            return; // máy dev/test không có ổ ghi hình
        }
        try {
            FileStore fs = Files.getFileStore(root);
            long total = fs.getTotalSpace();
            record(VOLUME, root.toString(), total, total - fs.getUsableSpace());
        } catch (IOException e) {
            log.warn("Không đo được dung lượng {}: {}", root, e.toString());
        }
    }

    /** Lưu số đo và báo cho mọi Tổ chức (ổ ghi hình dùng chung). */
    public StorageVolume record(String name, String path, long total, long used) {
        Instant now = clock.instant();
        storage.record(name, path, total, used, now);
        StorageVolume v = view(new Volume(name, path, total, used, now));
        StorageReading reading = new StorageReading(name, total, used, v.usedPercent(), v.daysLeft());
        for (UUID org : recordings.organizations()) {
            try {
                OrganizationContext.runAs(org, () -> alerting.onStorageMeasured(reading));
            } catch (RuntimeException e) {
                log.warn("Không đánh giá được cảnh báo lưu trữ cho Tổ chức {}: {}", org, e.toString());
            }
        }
        return v;
    }

    public List<StorageVolume> volumes() {
        return storage.volumes().stream().map(this::view).toList();
    }

    StorageVolume view(Volume v) {
        double percent = v.totalBytes() == 0 ? 0 : Math.round(v.usedBytes() * 1000.0 / v.totalBytes()) / 10.0;
        Double daysLeft = storage.baseline(v.name(), v.measuredAt().minus(Duration.ofHours(24)))
                .map(b -> daysLeft(v.totalBytes() - v.usedBytes(), v.usedBytes() - b.usedBytes(),
                        Duration.between(b.at(), v.measuredAt())))
                .orElse(null);
        return new StorageVolume(v.name(), v.totalBytes(), v.usedBytes(), percent, daysLeft, v.measuredAt());
    }

    /** Số ngày còn lại theo tốc độ tăng; null khi chưa đủ 1 giờ dữ liệu hoặc dung lượng không tăng. */
    static Double daysLeft(long freeBytes, long growthBytes, Duration over) {
        if (over.compareTo(Duration.ofHours(1)) < 0 || growthBytes <= 0) {
            return null;
        }
        double perDay = growthBytes * (86_400.0 / over.toSeconds());
        return Math.round(freeBytes / perDay * 10) / 10.0;
    }
}
