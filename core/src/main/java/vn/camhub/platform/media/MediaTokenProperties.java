package vn.camhub.platform.media;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param secret         khóa HMAC (base64, tối thiểu 32 byte), lấy từ Docker secret
 * @param ttl            thời hạn token, mặc định 60 giây (SADD 4)
 * @param internalUser   tài khoản nội bộ của MediaMTX: lệnh FFmpeg chuyển mã (runOnDemand) đọc luồng gốc và
 *                       publish luồng H.264; bộ giả lập RTSP publish ở dev/PoC. Không bao giờ cấp cho trình duyệt.
 * @param internalSecret mật khẩu tài khoản nội bộ; để trống thì tắt tài khoản này
 */
@ConfigurationProperties("camhub.media.token")
public record MediaTokenProperties(String secret, Duration ttl, String internalUser, String internalSecret) {

    public MediaTokenProperties {
        if (ttl == null) {
            ttl = Duration.ofSeconds(60);
        }
    }
}
