package vn.camhub.recording.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import vn.camhub.access.api.AccessApi;
import vn.camhub.access.api.Permission;
import vn.camhub.device.api.DeviceApi;
import vn.camhub.device.api.DeviceApi.CameraMedia;
import vn.camhub.organization.api.OrganizationApi;
import vn.camhub.platform.audit.AuditableEvent;
import vn.camhub.platform.media.MediaTokenService;
import vn.camhub.platform.security.ApiException;
import vn.camhub.platform.tenancy.OrganizationContext;
import vn.camhub.recording.infrastructure.RecordingRepository;
import vn.camhub.recording.infrastructure.RecordingRepository.Segment;

/**
 * Xem lại (US-17, UC-31): timeline gồm khoảng có ghi, mốc sự kiện, khoảng giữ lại; phiên phát lại qua máy chủ playback
 * của MediaMTX (fMP4) với token PLAYBACK 60 giây. Chọn vào khoảng trống thì nhảy tới đoạn có dữ liệu gần nhất (AC2).
 * Mỗi phiên phát lại ghi nhật ký VIEW_PLAYBACK.
 */
@Service
public class PlaybackService {

    /** Khoảng hở nhỏ hơn mức này giữa hai đoạn liền nhau vẫn coi là liên tục (ranh giới đoạn 60 giây). */
    static final Duration MAX_GAP = Duration.ofSeconds(3);
    static final Duration MAX_SESSION = Duration.ofHours(1);

    public record Range(Instant start, Instant end) {
    }

    public record Hold(UUID id, Instant start, Instant end, String reason) {
    }

    public record Timeline(List<Range> recordings, List<Instant> events, List<Hold> holds) {
    }

    /** {@code jumped}: thời điểm chọn không có dữ liệu, phiên bắt đầu ở đoạn gần nhất. */
    public record PlaybackSession(UUID cameraId, Instant requestedAt, Instant start, Instant end, boolean jumped,
            String url, String token, Instant expiresAt) {
    }

    private final RecordingRepository repo;
    private final DeviceApi devices;
    private final AccessApi access;
    private final OrganizationApi organizations;
    private final MediaTokenService tokens;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public PlaybackService(RecordingRepository repo, DeviceApi devices, AccessApi access, OrganizationApi organizations,
            MediaTokenService tokens, ApplicationEventPublisher events, Clock clock) {
        this.repo = repo;
        this.devices = devices;
        this.access = access;
        this.organizations = organizations;
        this.tokens = tokens;
        this.events = events;
        this.clock = clock;
    }

    public Timeline timeline(UUID user, UUID cameraId, Instant from, Instant to) {
        require(user, cameraId);
        if (Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0) {
            throw ApiException.badRequest("RANGE_TOO_LONG", "Chọn khoảng tối đa 31 ngày");
        }
        List<Hold> holds = repo.holds(cameraId).stream()
                .filter(h -> h.releasedAt() == null && h.startAt().isBefore(to) && h.endAt().isAfter(from))
                .map(h -> new Hold(h.id(), h.startAt(), h.endAt(), h.reason())).toList();
        return new Timeline(merge(repo.segments(cameraId, from, to)), repo.events(cameraId, from, to), holds);
    }

    public List<String> days(UUID user, UUID cameraId, YearMonth month) {
        CameraMedia m = require(user, cameraId);
        ZoneId tz = ZoneId.of(organizations.siteTimezone(m.siteId()));
        LocalDate first = month.atDay(1);
        return repo.recordingDays(cameraId, first.atStartOfDay(tz).toInstant(),
                first.plusMonths(1).atStartOfDay(tz).toInstant(), tz.getId());
    }

    public PlaybackSession open(UUID user, UUID cameraId, Instant at) {
        CameraMedia m = require(user, cameraId);
        Segment seg = repo.firstSegmentAfter(cameraId, at)
                .or(() -> repo.lastSegmentBefore(cameraId, at))
                .orElseThrow(() -> new ApiException(404, "NO_RECORDING", "Camera chưa có video ghi"));
        boolean inside = !seg.startAt().isAfter(at) && seg.endAt().isAfter(at);
        Instant start = inside ? at : seg.startAt();
        // Kéo dài tới hết khoảng ghi liên tục, tối đa 1 giờ (trình duyệt xin phiên mới khi phát hết)
        List<Range> ranges = merge(repo.segments(cameraId, start, start.plus(MAX_SESSION)));
        Instant end = ranges.isEmpty() ? seg.endAt() : ranges.get(0).end();
        if (end.isAfter(start.plus(MAX_SESSION))) {
            end = start.plus(MAX_SESSION);
        }
        String token = tokens.issue(m.mediaPath(), MediaTokenService.Action.PLAYBACK, user,
                OrganizationContext.require());
        long seconds = Math.max(1, Duration.between(start, end).toSeconds());
        String url = "/media/playback/get?path=" + m.mediaPath() + "&start=" + start + "&duration=" + seconds
                + "&format=fmp4&token=" + token;
        events.publishEvent(AuditableEvent.of(user, "VIEW_PLAYBACK", "CAMERA", cameraId.toString(),
                Map.of("targetName", m.name(), "from", start.toString(), "to", end.toString())));
        return new PlaybackSession(cameraId, at, start, end, !inside, url, token, tokens.expiryOf(token));
    }

    static List<Range> merge(List<Segment> segs) {
        List<Range> out = new ArrayList<>();
        for (Segment s : segs) {
            if (!out.isEmpty() && !s.startAt().isAfter(out.get(out.size() - 1).end().plus(MAX_GAP))) {
                Range last = out.remove(out.size() - 1);
                out.add(new Range(last.start(), s.endAt().isAfter(last.end()) ? s.endAt() : last.end()));
            } else {
                out.add(new Range(s.startAt(), s.endAt()));
            }
        }
        return out;
    }

    private CameraMedia require(UUID user, UUID cameraId) {
        CameraMedia m = devices.media(cameraId).orElseThrow(() -> ApiException.notFound("camera"));
        // INV-18: quyền xem video cũ tính theo vị trí hiện tại của camera
        if (!access.canOnCamera(user, Permission.VIEW_PLAYBACK, cameraId)) {
            throw ApiException.forbidden();
        }
        return m;
    }

    Instant now() {
        return clock.instant();
    }
}
