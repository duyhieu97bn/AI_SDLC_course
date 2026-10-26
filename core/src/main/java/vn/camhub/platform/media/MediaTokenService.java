package vn.camhub.platform.media;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

/**
 * Token ký HMAC-SHA256, hết hạn sau 60 giây, cho mỗi phiên xem trực tiếp hoặc phát lại (SADD 3.9.2, 4).
 * MediaMTX không biết mô hình quyền; nó chuyển token về Core qua {@code /internal/media/authorize}.
 *
 * <p>Định dạng: {@code base64url(payload JSON) "." base64url(HMAC(payload))}.
 */
@Service
public class MediaTokenService {

    public enum Action { READ, PLAYBACK }

    /** Nội dung token. {@code path} là đường dẫn MediaMTX, ví dụ {@code cam-3f2a...}. */
    public record Claims(
            @JsonProperty("p") String path,
            @JsonProperty("a") Action action,
            @JsonProperty("u") UUID userId,
            @JsonProperty("o") UUID organizationId,
            @JsonProperty("exp") long expiresAt) {
    }

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    /** Hậu tố đường dẫn chuyển mã H.265 → H.264 (SADD 3.9.2); token của camera dùng được cho cả luồng này. */
    public static final String TRANSCODE_SUFFIX = "_h264";

    private final SecretKeySpec key;
    private final MediaTokenProperties props;
    private final ObjectMapper json;
    private final Clock clock;

    public MediaTokenService(MediaTokenProperties props, ObjectMapper json, Clock clock) {
        if (props.secret() == null || props.secret().isBlank()) {
            throw new IllegalStateException("Chưa cấu hình camhub.media.token.secret");
        }
        byte[] raw = Base64.getDecoder().decode(props.secret().trim());
        if (raw.length < 32) {
            throw new IllegalStateException("camhub.media.token.secret phải dài tối thiểu 32 byte");
        }
        this.key = new SecretKeySpec(raw, "HmacSHA256");
        this.props = props;
        this.json = json;
        this.clock = clock;
    }

    public String issue(String path, Action action, UUID userId, UUID organizationId) {
        long exp = clock.instant().plus(props.ttl()).getEpochSecond();
        try {
            String body = B64.encodeToString(json.writeValueAsBytes(new Claims(path, action, userId, organizationId, exp)));
            return body + "." + B64.encodeToString(sign(body));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public Instant expiryOf(String token) {
        return parse(token).map(c -> Instant.ofEpochSecond(c.expiresAt())).orElseThrow();
    }

    /** Kiểm tra chữ ký, hạn dùng, hành động và đường dẫn. Trả về claims nếu hợp lệ. */
    public Optional<Claims> verify(String token, String path, Action action) {
        return parse(token)
                .filter(c -> c.expiresAt() >= clock.instant().getEpochSecond())
                .filter(c -> c.action() == action)
                .filter(c -> c.path().equals(path) || (c.path() + TRANSCODE_SUFFIX).equals(path));
    }

    private Optional<Claims> parse(String token) {
        if (token == null) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            return Optional.empty();
        }
        String body = token.substring(0, dot);
        try {
            byte[] given = B64D.decode(token.substring(dot + 1));
            if (!MessageDigest.isEqual(given, sign(body))) {
                return Optional.empty();
            }
            return Optional.of(json.readValue(B64D.decode(body), Claims.class));
        } catch (IllegalArgumentException | java.io.IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Chữ ký cho liên kết tải có hạn (US-20: URL tải clip ký 5 phút). {@code resource} là chuỗi mô tả tài nguyên, ví dụ
     * {@code export:<org>:<id>}; liên kết hợp lệ khi chữ ký khớp và chưa quá {@code expiresAtEpoch}.
     */
    public String signLink(String resource, long expiresAtEpoch) {
        return B64.encodeToString(sign("link|" + resource + "|" + expiresAtEpoch));
    }

    public boolean verifyLink(String resource, long expiresAtEpoch, String signature) {
        if (signature == null || expiresAtEpoch < clock.instant().getEpochSecond()) {
            return false;
        }
        try {
            return MessageDigest.isEqual(B64D.decode(signature), sign("link|" + resource + "|" + expiresAtEpoch));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Tài khoản nội bộ của MediaMTX (xem {@link MediaTokenProperties#internalUser()}). */
    public boolean isInternal(String user, String secret) {
        if (props.internalUser() == null || props.internalSecret() == null || props.internalSecret().isBlank()) {
            return false;
        }
        return props.internalUser().equals(user) && MessageDigest.isEqual(
                props.internalSecret().getBytes(StandardCharsets.UTF_8),
                (secret == null ? "" : secret).getBytes(StandardCharsets.UTF_8));
    }

    private byte[] sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(body.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
