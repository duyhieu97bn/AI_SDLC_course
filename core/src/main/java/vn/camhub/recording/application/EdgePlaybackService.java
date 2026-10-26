package vn.camhub.recording.application;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import vn.camhub.access.api.AccessApi;
import vn.camhub.access.api.Permission;
import vn.camhub.device.api.DeviceApi;
import vn.camhub.device.api.DeviceApi.CameraMedia;
import vn.camhub.device.api.DeviceControlPort.DeviceException;
import vn.camhub.device.api.DeviceControlPort.DeviceRecording;
import vn.camhub.device.api.DeviceControlPort.RecordingRange;
import vn.camhub.device.api.DeviceControlPort.RecordingSearch;
import vn.camhub.platform.audit.AuditableEvent;
import vn.camhub.platform.media.MediaTokenService;
import vn.camhub.platform.security.ApiException;
import vn.camhub.platform.tenancy.OrganizationContext;

/**
 * Xem lại từ thiết bị (US-22, UC-34): timeline nguồn DEVICE từ thẻ nhớ camera/đầu ghi (ONVIF Profile G); phát qua path
 * tạm của MediaMTX (WebRTC/HLS). Camera không hỗ trợ thì nguồn "Thiết bị" bị vô hiệu kèm lý do (AC2).
 */
@Service
public class EdgePlaybackService {

    static final String NOT_SUPPORTED = "Camera không hỗ trợ lưu trữ trên thiết bị (ONVIF Profile G)";

    public record EdgeRange(Instant start, Instant end, String recordingToken) {
    }

    public record EdgeTimeline(boolean supported, String reason, List<EdgeRange> ranges) {
    }

    public record EdgeSession(UUID cameraId, Instant start, String recordingToken, String whepUrl, String hlsUrl,
            String token) {
    }

    private final DeviceApi devices;
    private final AccessApi access;
    private final MediaTokenService tokens;
    private final ApplicationEventPublisher events;

    public EdgePlaybackService(DeviceApi devices, AccessApi access, MediaTokenService tokens,
            ApplicationEventPublisher events) {
        this.devices = devices;
        this.access = access;
        this.tokens = tokens;
        this.events = events;
    }

    public EdgeTimeline timeline(UUID user, UUID cameraId, Instant from, Instant to) {
        CameraMedia m = require(user, cameraId);
        if (Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0) {
            throw ApiException.badRequest("RANGE_TOO_LONG", "Chọn khoảng tối đa 31 ngày");
        }
        if (!m.edgeRecording()) {
            return new EdgeTimeline(false, NOT_SUPPORTED, List.of());
        }
        RecordingSearch r = search(cameraId, from, to);
        if (!r.supported()) {
            return new EdgeTimeline(false, r.reason() == null ? NOT_SUPPORTED : r.reason(), List.of());
        }
        List<EdgeRange> ranges = new ArrayList<>();
        for (DeviceRecording rec : r.recordings()) {
            for (RecordingRange rr : rec.ranges()) {
                ranges.add(new EdgeRange(rr.start(), rr.end(), rec.recordingToken()));
            }
        }
        ranges.sort(Comparator.comparing(EdgeRange::start));
        return new EdgeTimeline(true, null, ranges);
    }

    /** Phát từ {@code at}; rơi vào khoảng trống thì bắt đầu ở đoạn kế tiếp. */
    public EdgeSession open(UUID user, UUID cameraId, Instant at) {
        CameraMedia m = require(user, cameraId);
        if (!m.edgeRecording()) {
            throw new ApiException(409, "EDGE_NOT_SUPPORTED", NOT_SUPPORTED);
        }
        EdgeTimeline tl = timeline(user, cameraId, at.minus(Duration.ofDays(1)), at.plus(Duration.ofDays(1)));
        EdgeRange range = tl.ranges().stream().filter(r -> !r.start().isAfter(at) && r.end().isAfter(at)).findFirst()
                .or(() -> tl.ranges().stream().filter(r -> r.start().isAfter(at)).findFirst())
                .orElseThrow(() -> new ApiException(404, "NO_RECORDING", "Thiết bị không có video tại thời điểm này"));
        Instant start = range.start().isAfter(at) ? range.start() : at;
        String path;
        try {
            path = devices.openEdgeStream(cameraId, range.recordingToken(), start);
        } catch (DeviceException e) {
            throw new ApiException(422, e.code(), e.getMessage());
        }
        String token = tokens.issue(path, MediaTokenService.Action.READ, user, OrganizationContext.require());
        events.publishEvent(AuditableEvent.of(user, "VIEW_PLAYBACK", "CAMERA", cameraId.toString(),
                Map.of("targetName", m.name(), "from", start.toString(), "source", "DEVICE")));
        return new EdgeSession(cameraId, start, range.recordingToken(), "/media/webrtc/" + path + "/whep?token=" + token,
                "/media/hls/" + path + "/index.m3u8?token=" + token, token);
    }

    private RecordingSearch search(UUID cameraId, Instant from, Instant to) {
        try {
            return devices.edgeRecordings(cameraId, from, to);
        } catch (DeviceException e) {
            throw new ApiException(422, e.code(), e.getMessage());
        }
    }

    private CameraMedia require(UUID user, UUID cameraId) {
        CameraMedia m = devices.media(cameraId).orElseThrow(() -> ApiException.notFound("camera"));
        if (!access.canOnCamera(user, Permission.VIEW_PLAYBACK, cameraId)) {
            throw ApiException.forbidden();
        }
        return m;
    }
}
