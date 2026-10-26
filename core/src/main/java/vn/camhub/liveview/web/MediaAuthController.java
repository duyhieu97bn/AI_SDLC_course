package vn.camhub.liveview.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import vn.camhub.access.api.AccessApi;
import vn.camhub.access.api.Permission;
import vn.camhub.device.api.DeviceApi;
import vn.camhub.platform.media.MediaTokenService;
import vn.camhub.platform.tenancy.OrganizationContext;
import vn.camhub.platform.media.MediaTokenService.Action;

/**
 * Xác thực ngoài của MediaMTX ({@code authMethod: http}). MediaMTX gọi endpoint này cho mỗi phiên
 * đọc/phát lại/publish; trả 2xx là cho phép, mã khác là từ chối.
 *
 * <p>Endpoint nội bộ: Nginx không định tuyến {@code /internal}, chỉ container trong mạng backend gọi được.
 * Kiểm tra token HMAC và quyền VIEW_LIVE/VIEW_PLAYBACK hiện tại trên camera của path (INV-10).
 */
@RestController
public class MediaAuthController {

    private static final Logger log = LoggerFactory.getLogger(MediaAuthController.class);

    /** Trường MediaMTX gửi (v1.x). Token xem lấy từ {@code token} (header Bearer) hoặc query {@code ?token=}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MediaAuthRequest(String user, String password, String token, String ip, String action,
            String path, String protocol, String id, String query) {
    }

    private final MediaTokenService tokens;
    private final MeterRegistry meters;
    private final DeviceApi devices;
    private final AccessApi access;

    public MediaAuthController(MediaTokenService tokens, MeterRegistry meters, DeviceApi devices, AccessApi access) {
        this.tokens = tokens;
        this.meters = meters;
        this.devices = devices;
        this.access = access;
    }

    @PostMapping("/internal/media/authorize")
    public ResponseEntity<Void> authorize(@RequestBody MediaAuthRequest req) {
        boolean internal = tokens.isInternal(req.user(), req.password());
        boolean allowed = switch (req.action() == null ? "" : req.action()) {
            case "read" -> internal || hasValidToken(req, Action.READ);
            case "playback" -> internal || hasValidToken(req, Action.PLAYBACK);
            case "publish" -> internal;
            default -> false;
        };
        meters.counter("camhub.media.authorize", "action", String.valueOf(req.action()),
                "result", allowed ? "allow" : "deny").increment();
        if (!allowed) {
            log.info("Từ chối phiên media action={} path={} protocol={} ip={}", req.action(), req.path(),
                    req.protocol(), req.ip());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok().build();
    }

    /**
     * Token hợp lệ cho path và hành động; với path của camera thì người cầm token còn phải có quyền trên camera
     * vào lúc mở phiên (INV-10): thu hồi quyền có hiệu lực với mọi phiên mới, kể cả token cấp trước đó.
     * Path tạm của màn hình kiểm tra kết nối ({@code test-...}) chỉ cần token.
     */
    private boolean hasValidToken(MediaAuthRequest req, Action action) {
        String token = firstNonBlank(req.token(), queryParam(req.query(), "token").orElse(null));
        String path = req.path() == null ? "" : req.path();
        var claims = tokens.verify(token, path, action);
        if (claims.isEmpty()) {
            return false;
        }
        // Path tạm: xem thử khi thêm camera, phát lại từ thiết bị (token chỉ cấp sau khi kiểm tra quyền)
        if (path.startsWith("test-") || path.startsWith("edge-")) {
            return true;
        }
        var owner = devices.cameraOfPath(path);
        if (owner.isEmpty() || !owner.get().organizationId().equals(claims.get().organizationId())) {
            return false;
        }
        Permission needed = action == Action.PLAYBACK ? Permission.VIEW_PLAYBACK : Permission.VIEW_LIVE;
        return OrganizationContext.callAs(owner.get().organizationId(),
                () -> access.canOnCamera(claims.get().userId(), needed, owner.get().cameraId()));
    }

    static Optional<String> queryParam(String query, String name) {
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return Optional.of(URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return Optional.empty();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
