package vn.camhub.liveview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.camhub.access.api.AccessApi;
import vn.camhub.access.api.Permission;
import vn.camhub.device.api.DeviceApi;
import vn.camhub.platform.media.MediaTokenProperties;
import vn.camhub.platform.media.MediaTokenService;
import vn.camhub.platform.media.MediaTokenService.Action;
import vn.camhub.liveview.web.MediaAuthController;
import vn.camhub.liveview.web.MediaAuthController.MediaAuthRequest;

class MediaTokenServiceTest {

    static final String SECRET = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());
    static final Instant T0 = Instant.parse("2026-10-26T02:00:00Z");

    final MutableClock clock = new MutableClock(T0);
    final MediaTokenService tokens = new MediaTokenService(
            new MediaTokenProperties(SECRET, Duration.ofSeconds(60), "sim", "sim-pass"), new ObjectMapper(), clock);
    final UUID user = UUID.randomUUID();
    final UUID org = UUID.randomUUID();
    final UUID camera = UUID.randomUUID();
    final DeviceApi devices = mock(DeviceApi.class);
    final AccessApi access = mock(AccessApi.class);
    final MediaAuthController controller = new MediaAuthController(tokens, new SimpleMeterRegistry(), devices, access);

    @BeforeEach
    void cameraOwnsPaths() {
        when(devices.cameraOfPath(startsWith("cam-"))).thenReturn(Optional.of(new DeviceApi.PathOwner(camera, org)));
        when(access.canOnCamera(eq(user), any(), eq(camera))).thenReturn(true);
    }

    @Test
    void validTokenForSamePathAndAction() {
        String t = tokens.issue("cam-1", Action.READ, user, org);
        assertThat(tokens.verify(t, "cam-1", Action.READ)).hasValueSatisfying(c -> {
            assertThat(c.userId()).isEqualTo(user);
            assertThat(c.organizationId()).isEqualTo(org);
        });
    }

    @Test
    void tokenAlsoCoversTranscodedPath() {
        String t = tokens.issue("cam-1", Action.READ, user, org);
        assertThat(tokens.verify(t, "cam-1_h264", Action.READ)).isPresent();
    }

    @Test
    void rejectsOtherPathActionExpiredOrForged() {
        String t = tokens.issue("cam-1", Action.READ, user, org);
        assertThat(tokens.verify(t, "cam-2", Action.READ)).isEmpty();
        assertThat(tokens.verify(t, "cam-1", Action.PLAYBACK)).isEmpty();
        assertThat(tokens.verify(t.substring(0, t.length() - 2) + "AA", "cam-1", Action.READ)).isEmpty();
        assertThat(tokens.verify("garbage", "cam-1", Action.READ)).isEmpty();
        assertThat(tokens.verify(null, "cam-1", Action.READ)).isEmpty();

        clock.now = T0.plusSeconds(61);
        assertThat(tokens.verify(t, "cam-1", Action.READ)).isEmpty();
    }

    /** Tiêu chí TS-03 "Xác thực từng phiên xem": MediaMTX hỏi Core cho mỗi phiên, thiếu/sai token thì bị từ chối. */
    @Test
    void authorizeEndpointChecksEverySession() {
        String t = tokens.issue("cam-1", Action.READ, user, org);
        assertThat(status(new MediaAuthRequest("", "", null, "10.0.0.5", "read", "cam-1", "webrtc", "s1",
                "token=" + t))).isEqualTo(200);
        assertThat(status(new MediaAuthRequest("", "", t, "10.0.0.5", "read", "cam-1", "webrtc", "s2", "")))
                .isEqualTo(200);
        assertThat(status(new MediaAuthRequest("", "", null, "10.0.0.5", "read", "cam-1", "webrtc", "s3", "")))
                .isEqualTo(401);
        assertThat(status(new MediaAuthRequest("", "", null, "10.0.0.5", "read", "cam-9", "hls", "s4",
                "token=" + t))).isEqualTo(401);
        assertThat(status(new MediaAuthRequest("", "", null, "10.0.0.5", "api", "", "", "s5", ""))).isEqualTo(401);
    }

    @Test
    void publishOnlyWithInternalCredentials() {
        assertThat(status(new MediaAuthRequest("sim", "sim-pass", null, "", "publish", "sim-1", "rtsp", "p", "")))
                .isEqualTo(200);
        assertThat(status(new MediaAuthRequest("sim", "wrong", null, "", "publish", "sim-1", "rtsp", "p", "")))
                .isEqualTo(401);
        String viewerToken = tokens.issue("sim-1", Action.READ, user, org);
        assertThat(status(new MediaAuthRequest("", "", viewerToken, "", "publish", "sim-1", "rtsp", "p", "")))
                .isEqualTo(401);
    }

    /** Token còn hạn nhưng người dùng đã bị thu hồi quyền trên camera: phiên mới bị từ chối (US-32 AC2). */
    @Test
    void revokedPermissionDeniesNewSessionEvenWithValidToken() {
        String t = tokens.issue("cam-1", Action.READ, user, org);
        when(access.canOnCamera(user, Permission.VIEW_LIVE, camera))
                .thenReturn(false);
        assertThat(status(new MediaAuthRequest("", "", t, "10.0.0.5", "read", "cam-1", "webrtc", "s9", "")))
                .isEqualTo(401);
    }

    /** Token của Tổ chức khác không mở được camera của Tổ chức này (INV-01). */
    @Test
    void tokenFromAnotherOrganizationIsDenied() {
        String t = tokens.issue("cam-1", Action.READ, user, UUID.randomUUID());
        assertThat(status(new MediaAuthRequest("", "", t, "10.0.0.5", "read", "cam-1", "webrtc", "s10", "")))
                .isEqualTo(401);
    }

    /** FFmpeg chuyển mã trong MediaMTX đọc luồng gốc bằng tài khoản nội bộ, không có token người xem. */
    @Test
    void internalAccountMayReadForTranscoding() {
        assertThat(status(new MediaAuthRequest("sim", "sim-pass", null, "127.0.0.1", "read", "cam-1", "rtsp", "t",
                ""))).isEqualTo(200);
        assertThat(status(new MediaAuthRequest("sim", "", null, "127.0.0.1", "read", "cam-1", "rtsp", "t", "")))
                .isEqualTo(401);
    }

    private int status(MediaAuthRequest r) {
        return controller.authorize(r).getStatusCode().value();
    }

    static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
