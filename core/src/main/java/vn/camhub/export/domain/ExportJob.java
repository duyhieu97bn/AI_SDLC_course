package vn.camhub.export.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Công việc xuất clip (US-20, UC-33). Vòng đời QUEUED → PROCESSING → READY → EXPIRED; QUEUED/PROCESSING có thể
 * sang FAILED. Clip READY hết hạn sau 24 giờ.
 */
public record ExportJob(UUID id, UUID cameraId, String cameraName, Instant startAt, Instant endAt, Status status,
        UUID requestedBy, Instant requestedAt, Instant completedAt, Instant expiresAt, String bucket, String objectKey,
        Long sizeBytes, String errorCode, String errorMessage) {

    public static final Duration MAX_LENGTH = Duration.ofMinutes(60);
    public static final Duration TIME_TO_LIVE = Duration.ofHours(24);

    public enum Status {
        QUEUED, PROCESSING, READY, FAILED, EXPIRED;

        /** Chuyển trạng thái hợp lệ; kết quả trùng lặp/đến muộn từ Media Worker bị bỏ qua. */
        public boolean canMoveTo(Status next) {
            return switch (this) {
                case QUEUED -> Set.of(PROCESSING, READY, FAILED).contains(next);
                case PROCESSING -> Set.of(READY, FAILED).contains(next);
                case READY -> next == EXPIRED;
                case FAILED, EXPIRED -> false;
            };
        }
    }

    /** Khoảng xuất hợp lệ: kết thúc sau bắt đầu, dài tối đa 60 phút (US-20 AC2). */
    public static boolean validRange(Instant start, Instant end) {
        return end.isAfter(start) && Duration.between(start, end).compareTo(MAX_LENGTH) <= 0;
    }

    public boolean expiredAt(Instant now) {
        return status == Status.EXPIRED || (status == Status.READY && expiresAt != null && !now.isBefore(expiresAt));
    }
}
