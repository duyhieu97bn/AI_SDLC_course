package vn.camhub.liveview.application;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.camhub.access.api.AccessApi;
import vn.camhub.access.api.Permission;
import vn.camhub.device.api.DeviceApi;
import vn.camhub.device.api.DeviceApi.CameraMedia;
import vn.camhub.platform.media.MediaTokenService;
import vn.camhub.platform.security.ApiException;
import vn.camhub.platform.tenancy.OrganizationContext;

/**
 * Cấp phiên xem trực tiếp (US-08, SADD 3.9.2): kiểm tra VIEW_LIVE (INV-10), chọn luồng chính/phụ, chọn path chuyển
 * mã khi trình duyệt không phát được (H.265, B-frame), cấp token HMAC 60 giây. URL camera không bao giờ ra trình duyệt.
 */
@Service
public class LiveSessionService {

    public enum Stream { MAIN, SUB }

    /** {@code recording}: camera đang ghi hình – ô xem hiển thị "REC" (US-16 AC2). */
    public record LiveSession(UUID cameraId, String cameraName, String status, boolean ptz, boolean recording,
            Stream stream, boolean transcoded, String whepUrl, String hlsUrl, String token, Instant expiresAt) {
    }

    private final DeviceApi devices;
    private final AccessApi access;
    private final MediaTokenService tokens;

    public LiveSessionService(DeviceApi devices, AccessApi access, MediaTokenService tokens) {
        this.devices = devices;
        this.access = access;
        this.tokens = tokens;
    }

    public LiveSession open(UUID userId, UUID cameraId, Stream requested) {
        CameraMedia m = devices.media(cameraId).orElseThrow(() -> ApiException.notFound("camera"));
        if (!access.canOnCamera(userId, Permission.VIEW_LIVE, cameraId)) {
            throw ApiException.forbidden();
        }
        Stream stream = requested == Stream.SUB && m.hasSub() ? Stream.SUB : Stream.MAIN;
        boolean transcode = stream == Stream.SUB ? m.subNeedsTranscode() : m.needsTranscode();
        String path = (stream == Stream.SUB ? m.mediaPath() + "-sub" : m.mediaPath())
                + (transcode ? MediaTokenService.TRANSCODE_SUFFIX : "");
        String token = tokens.issue(path, MediaTokenService.Action.READ, userId, OrganizationContext.require());
        return new LiveSession(cameraId, m.name(), m.status(), m.ptz(), m.recording(), stream, transcode,
                "/media/webrtc/" + path + "/whep?token=" + token, "/media/hls/" + path + "/index.m3u8?token=" + token,
                token, tokens.expiryOf(token));
    }
}
