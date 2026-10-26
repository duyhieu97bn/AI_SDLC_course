package vn.camhub.liveview.application;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import vn.camhub.access.api.AccessApi;
import vn.camhub.access.api.Permission;
import vn.camhub.device.api.DeviceApi;
import vn.camhub.identity.api.IdentityApi;
import vn.camhub.liveview.infrastructure.PresetRepository;
import vn.camhub.platform.realtime.RealtimePublisher;
import vn.camhub.platform.security.ApiException;

/**
 * Điều khiển PTZ (US-12, US-13) với khóa theo camera (SADD 3.11.4): khóa giữ 30 giây, gia hạn mỗi lệnh. Người có
 * thứ hạng vai trò ≥ người đang giữ thì giành được khóa, người mất khóa nhận thông báo WebSocket; thấp hơn thì bị từ
 * chối kèm tên người đang điều khiển. Lua script bảo đảm kiểm tra và đặt khóa là nguyên tử ở mọi bản Core.
 */
@Service
public class PtzService {

    static final Duration LOCK_TTL = Duration.ofSeconds(30);

    /** Trả về {kết quả, userId người giữ trước, tên người giữ trước}: 0 = từ chối, 1 = giữ/gia hạn, 2 = giành khóa. */
    @SuppressWarnings("rawtypes") // RedisScript trả List phần tử hỗn hợp (số, chuỗi)
    private static final DefaultRedisScript<List> ACQUIRE = new DefaultRedisScript<>("""
            local cur = redis.call('GET', KEYS[1])
            local me = ARGV[1] .. '|' .. ARGV[2] .. '|' .. ARGV[3]
            if cur then
              local sep1 = string.find(cur, '|', 1, true)
              local sep2 = string.find(cur, '|', sep1 + 1, true)
              local uid = string.sub(cur, 1, sep1 - 1)
              local rank = tonumber(string.sub(cur, sep1 + 1, sep2 - 1))
              local name = string.sub(cur, sep2 + 1)
              if uid ~= ARGV[1] and rank > tonumber(ARGV[2]) then
                return {0, uid, name}
              end
              redis.call('SET', KEYS[1], me, 'EX', ARGV[4])
              if uid ~= ARGV[1] then return {2, uid, name} end
              return {1, uid, name}
            end
            redis.call('SET', KEYS[1], me, 'EX', ARGV[4])
            return {1, '', ''}
            """, List.class);

    public record PtzCommand(double pan, double tilt, double zoom) {
    }

    private final StringRedisTemplate redis;
    private final DeviceApi devices;
    private final AccessApi access;
    private final IdentityApi identity;
    private final PresetRepository presetsRepo;
    private final RealtimePublisher realtime;

    public PtzService(StringRedisTemplate redis, DeviceApi devices, AccessApi access, IdentityApi identity,
            PresetRepository presetsRepo, RealtimePublisher realtime) {
        this.redis = redis;
        this.devices = devices;
        this.access = access;
        this.identity = identity;
        this.presetsRepo = presetsRepo;
        this.realtime = realtime;
    }

    public void move(UUID userId, UUID cameraId, PtzCommand c) {
        lock(userId, cameraId);
        if (c.pan() == 0 && c.tilt() == 0 && c.zoom() == 0) {
            devices.ptzStop(cameraId);
        } else {
            devices.ptzMove(cameraId, c.pan(), c.tilt(), c.zoom());
        }
    }

    public void stop(UUID userId, UUID cameraId) {
        lock(userId, cameraId);
        devices.ptzStop(cameraId);
    }

    public record PresetView(String token, String name, String source) {
    }

    /** Preset có sẵn trên camera (DEVICE) và preset lưu từ CamHub (CAMHUB) – US-13 ghi chú kỹ thuật. */
    public List<PresetView> presets(UUID userId, UUID cameraId) {
        requirePtz(userId, cameraId);
        Map<String, String> ours = presetsRepo.tokens(cameraId);
        return devices.presets(cameraId).stream()
                .map(p -> new PresetView(p.token(), p.name(), ours.containsKey(p.token()) ? "CAMHUB" : "DEVICE"))
                .sorted(Comparator.comparing(PresetView::name, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    public void gotoPreset(UUID userId, UUID cameraId, String token) {
        lock(userId, cameraId);
        devices.gotoPreset(cameraId, token);
    }

    public PresetView savePreset(UUID userId, UUID cameraId, String name) {
        lock(userId, cameraId);
        String token = devices.setPreset(cameraId, name);
        presetsRepo.insert(cameraId, name, token, userId);
        return new PresetView(token, name, "CAMHUB");
    }

    public void removePreset(UUID userId, UUID cameraId, String token) {
        lock(userId, cameraId);
        devices.removePreset(cameraId, token);
        presetsRepo.delete(cameraId, token);
    }

    private void requirePtz(UUID userId, UUID cameraId) {
        if (!access.canOnCamera(userId, Permission.CONTROL_PTZ, cameraId)) {
            throw ApiException.forbidden();
        }
    }

    @SuppressWarnings("unchecked")
    private void lock(UUID userId, UUID cameraId) {
        requirePtz(userId, cameraId);
        int rank = access.rolesOf(userId).stream().mapToInt(AccessApi.RoleRef::rank).max().orElse(0);
        String name = identity.displayName(userId).orElse("?").replace("|", "/");
        List<Object> r = redis.execute(ACQUIRE, List.of("ptz:lock:" + cameraId), userId.toString(),
                String.valueOf(rank), name, String.valueOf(LOCK_TTL.toSeconds()));
        long result = ((Number) r.get(0)).longValue();
        if (result == 0) {
            throw new ApiException(409, "PTZ_LOCKED", "Đang được điều khiển bởi " + r.get(2),
                    Map.of("holder", String.valueOf(r.get(2))));
        }
        if (result == 2) {
            realtime.toUser(UUID.fromString((String) r.get(1)), "ptz",
                    Map.of("cameraId", cameraId.toString(), "takenBy", name));
        }
    }
}
