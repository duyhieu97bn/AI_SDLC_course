package vn.camhub.access.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import vn.camhub.access.domain.Grant;

/**
 * Cache quyền theo SADD 3.11.1: khóa {@code perm:{org}:{user}:{version}}, TTL 60 giây. Đổi quyền thì tăng
 * {@code permver:{user}}, khóa cũ không còn được đọc nên quyền mới có hiệu lực ngay ở mọi bản Core.
 * Redis lỗi thì đọc thẳng DB (chậm hơn nhưng vẫn đúng).
 */
@Component
public class PermissionCache {

    private static final Logger log = LoggerFactory.getLogger(PermissionCache.class);
    static final Duration TTL = Duration.ofSeconds(60);
    private static final TypeReference<List<Grant>> GRANTS = new TypeReference<>() {
    };

    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public PermissionCache(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    public List<Grant> get(UUID orgId, UUID userId, Supplier<List<Grant>> loader) {
        String key;
        try {
            String version = Optional.ofNullable(redis.opsForValue().get(versionKey(userId))).orElse("0");
            key = "perm:" + orgId + ":" + userId + ":" + version;
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                return json.readValue(cached, GRANTS);
            }
        } catch (Exception e) {
            log.warn("Không đọc được cache quyền, dùng DB: {}", e.toString());
            return loader.get();
        }
        List<Grant> grants = loader.get();
        try {
            redis.opsForValue().set(key, json.writeValueAsString(grants), TTL);
        } catch (Exception e) {
            log.warn("Không ghi được cache quyền: {}", e.toString());
        }
        return grants;
    }

    public void invalidate(UUID userId) {
        try {
            redis.opsForValue().increment(versionKey(userId));
        } catch (Exception e) {
            // Không tăng được version: cache cũ tự hết hạn sau tối đa 60 giây (US-32 AC2 vẫn đạt)
            log.warn("Không tăng được version quyền của {}: {}", userId, e.toString());
        }
    }

    private static String versionKey(UUID userId) {
        return "permver:" + userId;
    }
}
