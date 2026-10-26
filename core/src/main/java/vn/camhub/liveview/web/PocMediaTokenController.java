package vn.camhub.liveview.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import vn.camhub.platform.media.MediaTokenService;
import vn.camhub.platform.media.MediaTokenService.Action;

/**
 * Chỉ bật với profile {@code poc}: cấp token cho trang đo PoC khi chưa có đăng nhập và quyền.
 * S3 thay bằng {@code POST /api/v1/cameras/{id}/live-session} (kiểm tra VIEW_LIVE).
 */
@RestController
@Profile("poc")
public class PocMediaTokenController {

    public record TokenRequest(@NotNull @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String path, Action action) {
    }

    public record TokenResponse(String token, String whepUrl, String hlsUrl, Instant expiresAt) {
    }

    private static final UUID POC_USER = new UUID(0, 1);
    private static final UUID POC_ORG = new UUID(0, 1);

    private final MediaTokenService tokens;

    public PocMediaTokenController(MediaTokenService tokens) {
        this.tokens = tokens;
    }

    @PostMapping("/api/v1/poc/media-token")
    public TokenResponse issue(@Valid @RequestBody TokenRequest req) {
        Action action = req.action() == null ? Action.READ : req.action();
        String token = tokens.issue(req.path(), action, POC_USER, POC_ORG);
        String q = "?token=" + token;
        return new TokenResponse(token, "/media/webrtc/" + req.path() + "/whep" + q,
                "/media/hls/" + req.path() + "/index.m3u8" + q, tokens.expiryOf(token));
    }
}
