package vn.camhub.liveview.application;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.camhub.access.api.AccessApi;
import vn.camhub.access.api.Permission;
import vn.camhub.device.api.DeviceApi;
import vn.camhub.device.api.DeviceApi.CameraMedia;
import vn.camhub.device.api.DeviceControlPort.TalkSession;
import vn.camhub.device.api.DeviceControlPort.TalkStatus;
import vn.camhub.liveview.application.TalkAuditService.Pending;
import vn.camhub.platform.audit.AuditableEvent;
import vn.camhub.platform.security.ApiException;
import vn.camhub.platform.tenancy.OrganizationContext;

/**
 * Nhấn giữ để nói (US-15, UC-25). Core kiểm tra quyền TALK_AUDIO rồi nhờ Gateway mở kênh backchannel ONVIF tới loa
 * camera; trình duyệt gửi âm thanh thẳng tới Gateway qua WebSocket {@code /talk/ws/{sessionId}} (Nginx chuyển tiếp),
 * mã phiên ngẫu nhiên dùng một lần là thông tin xác thực. Nhả nút thì kết thúc và ghi TALK_AUDIO kèm thời lượng.
 */
@Service
public class TalkService {

    public record TalkStart(String sessionId, String wsUrl, String codec, int sampleRate, int expiresInSeconds) {
    }

    public record TalkEnd(String sessionId, double durationSeconds, double audioSeconds) {
    }

    private final DeviceApi devices;
    private final AccessApi access;
    private final TalkAuditService audit;

    public TalkService(DeviceApi devices, AccessApi access, TalkAuditService audit) {
        this.devices = devices;
        this.access = access;
        this.audit = audit;
    }

    public TalkStart start(UUID userId, UUID cameraId) {
        CameraMedia camera = devices.media(cameraId).orElseThrow(() -> ApiException.notFound("camera"));
        if (!access.canOnCamera(userId, Permission.TALK_AUDIO, cameraId)) {
            throw ApiException.forbidden();
        }
        TalkSession s = devices.openTalk(cameraId);
        audit.register(new Pending(s.sessionId(), userId, OrganizationContext.require(), cameraId, camera.name(),
                AuditableEvent.currentIp(), Instant.now()));
        return new TalkStart(s.sessionId(), "/talk/ws/" + s.sessionId(), s.codec(), s.sampleRate(),
                s.expiresInSeconds());
    }

    /** Chỉ người mở phiên kết thúc được phiên của mình. */
    public TalkEnd end(UUID userId, UUID cameraId, String sessionId) {
        Pending p = audit.find(sessionId)
                .filter(x -> x.userId().equals(userId) && x.cameraId().equals(cameraId))
                .orElseThrow(() -> ApiException.notFound("talk-session"));
        var status = devices.closeTalk(sessionId);
        audit.complete(p, status);
        return new TalkEnd(sessionId, status.map(TalkStatus::durationSeconds).orElse(0.0),
                status.map(TalkStatus::audioSeconds).orElse(0.0));
    }
}
