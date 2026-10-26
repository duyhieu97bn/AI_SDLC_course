package vn.camhub.recording.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.camhub.platform.security.CurrentUser;
import vn.camhub.recording.application.EdgePlaybackService;
import vn.camhub.recording.application.PlaybackService;
import vn.camhub.recording.application.PlaybackService.PlaybackSession;
import vn.camhub.recording.application.PlaybackService.Timeline;
import vn.camhub.recording.application.RecordingService;
import vn.camhub.recording.application.RecordingService.BulkResult;
import vn.camhub.recording.application.RetentionService;
import vn.camhub.recording.application.RetentionService.PurgeResult;
import vn.camhub.recording.domain.RecordingPolicy;
import vn.camhub.recording.domain.RecordingPolicy.Mode;
import vn.camhub.recording.domain.RecordingPolicy.Slot;
import vn.camhub.recording.infrastructure.RecordingRepository.Hold;

/** Ghi hình (US-16), xem lại (US-17), lưu trữ và giữ lại (US-21) – SADD 3.7.1 nhóm "Ghi hình". */
@RestController
public class RecordingController {

    public record PolicyBody(@NotNull Mode mode, List<Slot> schedule, @Min(0) @Max(60) int preEventSeconds,
            @Min(0) @Max(600) int postEventSeconds) {
        RecordingPolicy toPolicy(UUID cameraId) {
            return new RecordingPolicy(cameraId, mode, schedule == null ? List.of() : schedule, preEventSeconds,
                    postEventSeconds);
        }
    }

    public record BulkPolicyBody(@NotNull List<UUID> cameraIds, @NotNull @Valid PolicyBody policy) {
    }

    public record PlaybackBody(@NotNull Instant at) {
    }

    public record RetentionBody(@Min(1) @Max(3650) int defaultRetentionDays) {
    }

    public record OverrideBody(@Min(1) @Max(3650) Integer retentionDays) {
    }

    public record HoldBody(@NotNull UUID cameraId, @NotNull Instant startAt, @NotNull Instant endAt,
            @NotBlank @Size(max = 500) String reason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SegmentCompleted(String path, String file, String duration) {
    }

    private final RecordingService recording;
    private final PlaybackService playback;
    private final RetentionService retention;
    private final EdgePlaybackService edge;

    public RecordingController(RecordingService recording, PlaybackService playback, RetentionService retention,
            EdgePlaybackService edge) {
        this.edge = edge;
        this.recording = recording;
        this.playback = playback;
        this.retention = retention;
    }

    @GetMapping("/api/v1/cameras/{id}/recording-policy")
    public RecordingPolicy policy(@PathVariable UUID id) {
        return recording.policy(me(), id);
    }

    @PutMapping("/api/v1/cameras/{id}/recording-policy")
    public RecordingPolicy setPolicy(@PathVariable UUID id, @Valid @RequestBody PolicyBody body) {
        return recording.setPolicy(me(), body.toPolicy(id));
    }

    @PutMapping("/api/v1/recording-policies")
    public List<BulkResult> setPolicies(@Valid @RequestBody BulkPolicyBody body) {
        return recording.setPolicies(me(), body.cameraIds(), body.policy().toPolicy(null));
    }

    @GetMapping("/api/v1/cameras/{id}/timeline")
    public Timeline timeline(@PathVariable UUID id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return playback.timeline(me(), id, from, to);
    }

    @GetMapping("/api/v1/cameras/{id}/recording-days")
    public List<String> days(@PathVariable UUID id, @RequestParam String month) {
        return playback.days(me(), id, YearMonth.parse(month));
    }

    @PostMapping("/api/v1/cameras/{id}/playback-session")
    public PlaybackSession playback(@PathVariable UUID id, @Valid @RequestBody PlaybackBody body) {
        return playback.open(me(), id, body.at());
    }

    // ---------------------------------------------------------------- xem lại từ thiết bị (US-22)

    @GetMapping("/api/v1/cameras/{id}/edge-timeline")
    public EdgePlaybackService.EdgeTimeline edgeTimeline(@PathVariable UUID id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return edge.timeline(me(), id, from, to);
    }

    @PostMapping("/api/v1/cameras/{id}/edge-playback-session")
    public EdgePlaybackService.EdgeSession edgePlayback(@PathVariable UUID id, @Valid @RequestBody PlaybackBody body) {
        return edge.open(me(), id, body.at());
    }

    @GetMapping("/api/v1/retention-policy")
    public Map<String, Integer> retention() {
        return Map.of("defaultRetentionDays", retention.defaultDays());
    }

    @PutMapping("/api/v1/retention-policy")
    public Map<String, Integer> setRetention(@Valid @RequestBody RetentionBody body) {
        retention.setDefaultDays(me(), body.defaultRetentionDays());
        return retention();
    }

    @GetMapping("/api/v1/cameras/{id}/retention")
    public Map<String, Object> cameraRetention(@PathVariable UUID id) {
        Integer days = retention.overrideDays(me(), id);
        return Map.of("override", days == null ? "" : days, "defaultRetentionDays", retention.defaultDays());
    }

    /** Ghi đè số ngày lưu cho camera; {@code retentionDays} null = dùng mặc định của Tổ chức. */
    @PutMapping("/api/v1/cameras/{id}/retention")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setCameraRetention(@PathVariable UUID id, @Valid @RequestBody OverrideBody body) {
        retention.setOverride(me(), id, body.retentionDays());
    }

    @GetMapping("/api/v1/cameras/{id}/evidence-holds")
    public List<Hold> holds(@PathVariable UUID id) {
        return retention.holds(me(), id);
    }

    @PostMapping("/api/v1/evidence-holds")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, UUID> hold(@Valid @RequestBody HoldBody body) {
        return Map.of("id", retention.hold(me(), body.cameraId(), body.startAt(), body.endAt(), body.reason()));
    }

    @PostMapping("/api/v1/evidence-holds/{id}/release")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@PathVariable UUID id) {
        retention.release(me(), id);
    }

    /** Chạy xóa video quá hạn ngay cho Tổ chức hiện tại (vận hành, kiểm thử); bình thường job chạy 02:00. */
    @PostMapping("/api/v1/retention/purge")
    @PreAuthorize("hasPermission(null, 'ORGANIZATION', 'MANAGE_SYSTEM')")
    public PurgeResult purge() {
        return retention.purgeOrganization();
    }

    /** Hook {@code runOnRecordSegmentComplete} của MediaMTX (mạng nội bộ, Nginx không định tuyến /internal). */
    @PostMapping("/internal/media/segments")
    public ResponseEntity<Void> segment(@RequestBody SegmentCompleted seg) {
        recording.indexSegment(seg.path(), seg.file(), seg.duration());
        return ResponseEntity.accepted().build();
    }

    private static UUID me() {
        return CurrentUser.require().userId();
    }
}
