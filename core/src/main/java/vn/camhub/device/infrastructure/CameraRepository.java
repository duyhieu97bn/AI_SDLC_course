package vn.camhub.device.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.camhub.access.api.ScopeFilter;
import vn.camhub.device.domain.Camera;

/** Bảng camera (module device là chủ). Mọi truy vấn chạy dưới RLS của Tổ chức hiện tại. */
@Repository
public class CameraRepository {

    public record Query(UUID siteId, UUID zoneId, UUID groupId, String status, String q, int page, int size) {
    }

    public record Page(List<Camera> items, long total) {
    }

    static final String COLUMNS = """
            c.id, c.site_id, c.zone_id, c.name, c.protocol, c.ip_address, c.port, c.stream_url, c.manufacturer,
            c.model, c.mac_address, c.status, c.last_offline_at, c.created_at, c.username, c.media_path,
            c.capabilities::text as capabilities, c.firmware, c.verified, c.status_changed_at, c.recorder_id,
            c.channel_no, c.connector_id,
            coalesce((select array_agg(m.group_id) from camera_group_member m where m.camera_id = c.id), '{}') as group_ids
            """;

    private final JdbcClient jdbc;

    public CameraRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Lọc theo phạm vi quyền (US-32 AC1) và bộ lọc giao diện (US-05 AC1: ≤ 1 giây với 500 camera). */
    public Page search(ScopeFilter scope, Query q) {
        if (scope.isEmpty()) {
            return new Page(List.of(), 0);
        }
        String like = q.q() == null || q.q().isBlank() ? null : "%" + q.q().trim().toLowerCase() + "%";
        List<Object> params = new ArrayList<>();
        String where = scopeClause(scope, params) + """
                 and (cast(? as uuid) is null or c.site_id = ?)
                 and (cast(? as uuid) is null or c.zone_id = ?)
                 and (cast(? as uuid) is null or exists (select 1 from camera_group_member g where g.camera_id = c.id and g.group_id = ?))
                 and (cast(? as text) is null or c.status = ?)
                 and (cast(? as text) is null or lower(c.name) like ? or c.ip_address like ? or lower(coalesce(c.model, '')) like ?)
                """;
        params.addAll(Arrays.asList(q.siteId(), q.siteId(), q.zoneId(), q.zoneId(), q.groupId(), q.groupId(),
                q.status(), q.status(), like, like, like, like));
        long total = jdbc.sql("select count(*) from camera c where " + where).params(params).query(Long.class).single();
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(q.size());
        pageParams.add((long) q.page() * q.size());
        List<Camera> items = jdbc.sql("select " + COLUMNS + " from camera c where " + where
                + " order by lower(c.name) limit ? offset ?").params(pageParams).query(CameraRepository::map).list();
        return new Page(items, total);
    }

    private static String scopeClause(ScopeFilter s, List<Object> params) {
        if (s.all()) {
            return "true";
        }
        params.add(s.siteIds().toArray(UUID[]::new));
        params.add(s.cameraIds().toArray(UUID[]::new));
        params.add(s.groupIds().toArray(UUID[]::new));
        return """
                (c.site_id = any(?) or c.id = any(?)
                 or exists (select 1 from camera_group_member m where m.camera_id = c.id and m.group_id = any(?)))
                """;
    }

    public Optional<Camera> find(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from camera c where c.id = ?").param(id).query(CameraRepository::map)
                .optional();
    }

    public Map<UUID, Camera> findAll(Collection<UUID> ids) {
        Map<UUID, Camera> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        jdbc.sql("select " + COLUMNS + " from camera c where c.id = any(?)").param(ids.toArray(UUID[]::new))
                .query(CameraRepository::map).list().forEach(c -> out.put(c.id(), c));
        return out;
    }

    public void relocate(UUID id, UUID siteId, UUID zoneId) {
        jdbc.sql("update camera set site_id = ?, zone_id = ?, updated_at = now() where id = ?")
                .params(siteId, zoneId, id).update();
    }

    public long countInZone(UUID zoneId) {
        return jdbc.sql("select count(*) from camera where zone_id = ?").param(zoneId).query(Long.class).single();
    }

    public Set<UUID> sitesOfGroup(UUID groupId) {
        return new HashSet<>(jdbc.sql("""
                select distinct c.site_id from camera c join camera_group_member m on m.camera_id = c.id
                where m.group_id = ?
                """).param(groupId).query(UUID.class).list());
    }

    public Set<UUID> sitesWithCameras(ScopeFilter scope) {
        if (scope.isEmpty()) {
            return Set.of();
        }
        List<Object> params = new ArrayList<>();
        String where = scopeClause(scope, params);
        return new HashSet<>(jdbc.sql("select distinct c.site_id from camera c where " + where).params(params)
                .query(UUID.class).list());
    }

    static Camera map(ResultSet rs, int i) throws SQLException {
        java.sql.Array arr = rs.getArray("group_ids");
        Set<UUID> groups = new HashSet<>();
        if (arr != null) {
            for (Object o : (Object[]) arr.getArray()) {
                groups.add((UUID) o);
            }
        }
        java.sql.Timestamp off = rs.getTimestamp("last_offline_at");
        java.sql.Timestamp changed = rs.getTimestamp("status_changed_at");
        return new Camera(rs.getObject("id", UUID.class), rs.getObject("site_id", UUID.class),
                rs.getObject("zone_id", UUID.class), rs.getString("name"), rs.getString("protocol"),
                rs.getString("ip_address"), (Integer) rs.getObject("port"), rs.getString("stream_url"),
                rs.getString("manufacturer"), rs.getString("model"), rs.getString("mac_address"),
                rs.getString("status"), off == null ? null : off.toInstant(), rs.getTimestamp("created_at").toInstant(),
                groups, rs.getString("username"), rs.getString("media_path"), rs.getString("capabilities"),
                rs.getString("firmware"), rs.getBoolean("verified"), changed == null ? null : changed.toInstant(),
                rs.getObject("recorder_id", UUID.class), (Integer) rs.getObject("channel_no"),
                rs.getObject("connector_id", UUID.class));
    }

    // ---------------------------------------------------------------- S2: thêm, xóa camera, luồng, trạng thái

    public record NewCamera(UUID id, UUID siteId, UUID zoneId, String name, String protocol, String ipAddress,
            Integer port, String streamUrl, String username, String passwordEnc, String manufacturer, String model,
            String macAddress, String firmware, String serialNumber, String capabilitiesJson, boolean verified,
            UUID recorderId, Integer channelNo, UUID connectorId) {
    }

    public void insert(NewCamera c) {
        jdbc.sql("""
                insert into camera (id, organization_id, site_id, zone_id, name, protocol, ip_address, port, stream_url,
                                    username, password_enc, manufacturer, model, mac_address, firmware, serial_number,
                                    capabilities, verified, status, status_changed_at, recorder_id, channel_no,
                                    connector_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, 'UNKNOWN', now(), ?, ?, ?)
                """).params(c.id(), vn.camhub.platform.tenancy.OrganizationContext.require(), c.siteId(), c.zoneId(),
                        c.name(), c.protocol(), c.ipAddress(), c.port(), c.streamUrl(), c.username(), c.passwordEnc(),
                        c.manufacturer(), c.model(), c.macAddress(), c.firmware(), c.serialNumber(),
                        c.capabilitiesJson() == null ? "{}" : c.capabilitiesJson(), c.verified(), c.recorderId(),
                        c.channelNo(), c.connectorId())
                .update();
    }

    public void insertStream(UUID cameraId, vn.camhub.device.domain.StreamInfo s) {
        jdbc.sql("""
                insert into camera_stream (organization_id, camera_id, stream_type, profile_token, codec, width, height,
                                           fps, bitrate_kbps, rtsp_url, b_frames)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (camera_id, stream_type) do update set profile_token = excluded.profile_token,
                    codec = excluded.codec, width = excluded.width, height = excluded.height, fps = excluded.fps,
                    bitrate_kbps = excluded.bitrate_kbps, rtsp_url = excluded.rtsp_url, b_frames = excluded.b_frames
                """).params(vn.camhub.platform.tenancy.OrganizationContext.require(), cameraId, s.type(),
                        s.profileToken(), s.codec(), s.width(), s.height(), s.fps(), s.bitrateKbps(), s.rtspUrl(),
                        s.bFrames())
                .update();
    }

    public List<vn.camhub.device.domain.StreamInfo> streams(UUID cameraId) {
        return jdbc.sql("""
                select stream_type, profile_token, codec, width, height, fps, bitrate_kbps, rtsp_url, b_frames
                from camera_stream where camera_id = ? order by stream_type
                """).param(cameraId).query((rs, i) -> new vn.camhub.device.domain.StreamInfo(rs.getString(1),
                        rs.getString(2), rs.getString(3), (Integer) rs.getObject(4), (Integer) rs.getObject(5),
                        (Integer) rs.getObject(6), (Integer) rs.getObject(7), rs.getString(8), rs.getBoolean(9)))
                .list();
    }

    public Optional<String> passwordEnc(UUID cameraId) {
        return jdbc.sql("select password_enc from camera where id = ?").param(cameraId).query(String.class)
                .optional();
    }

    public void recordClockDrift(UUID cameraId, double driftSeconds) {
        jdbc.sql("update camera set clock_drift_seconds = ?, clock_checked_at = now() where id = ?")
                .params(driftSeconds, cameraId).update();
    }

    /** Đổi IP: cập nhật địa chỉ và thay máy trong URL luồng đã lưu (luồng RTSP trỏ vào IP cũ). */
    public void updateHost(UUID cameraId, String oldHost, String newHost) {
        jdbc.sql("update camera set ip_address = ? where id = ?").params(newHost, cameraId).update();
        if (oldHost != null) {
            jdbc.sql("update camera_stream set rtsp_url = replace(rtsp_url, ?, ?) where camera_id = ?")
                    .params("//" + oldHost, "//" + newHost, cameraId).update();
        }
    }

    public java.util.Map<UUID, Double> clockDrifts() {
        java.util.Map<UUID, Double> out = new java.util.HashMap<>();
        jdbc.sql("select id, clock_drift_seconds from camera where clock_drift_seconds is not null")
                .query((rs, i) -> out.put(rs.getObject(1, UUID.class), rs.getDouble(2))).list();
        return out;
    }

    public void updatePassword(UUID cameraId, String passwordEnc) {
        jdbc.sql("update camera set password_enc = ? where id = ?").params(passwordEnc, cameraId).update();
    }

    public void delete(UUID cameraId) {
        jdbc.sql("delete from camera where id = ?").param(cameraId).update();
    }

    public boolean recordingEnabled(UUID cameraId) {
        return jdbc.sql("select recording_enabled from camera where id = ?").param(cameraId).query(Boolean.class)
                .optional().orElse(false);
    }

    public void setRecordingEnabled(UUID cameraId, boolean on) {
        jdbc.sql("update camera set recording_enabled = ?, updated_at = now() where id = ?").params(on, cameraId)
                .update();
    }

    public void rename(UUID cameraId, String name) {
        jdbc.sql("update camera set name = ?, updated_at = now() where id = ?").params(name, cameraId).update();
    }

    /** Thiết bị đã thêm, nhận diện theo MAC (chuẩn hóa chữ thường, bỏ dấu phân cách) hoặc theo IP. */
    public Set<String> knownMacs() {
        return new HashSet<>(jdbc.sql("""
                select lower(regexp_replace(mac_address, '[^0-9a-fA-F]', '', 'g')) from camera where mac_address is not null
                """).query(String.class).list());
    }

    public Set<String> knownHosts() {
        return new HashSet<>(jdbc.sql("select ip_address from camera where ip_address is not null").query(String.class)
                .list());
    }

    /** Cập nhật trạng thái nếu sự kiện mới hơn trạng thái đang lưu (sự kiện có thể đến không theo thứ tự). */
    public boolean updateStatus(UUID cameraId, String status, java.time.Instant at) {
        return jdbc.sql("""
                update camera set status = ?, status_changed_at = ?,
                       last_offline_at = case when ? = 'OFFLINE' then ? else last_offline_at end
                where id = ? and status <> ? and (status_changed_at is null or status_changed_at <= ?)
                """).params(status, java.sql.Timestamp.from(at), status, java.sql.Timestamp.from(at), cameraId, status,
                        java.sql.Timestamp.from(at)).update() == 1;
    }

    /** Mọi camera của Tổ chức hiện tại (đồng bộ MediaMTX, danh sách theo dõi kết nối). */
    public List<Camera> all() {
        return jdbc.sql("select " + COLUMNS + " from camera c").query(CameraRepository::map).list();
    }

    public List<UUID> organizations() {
        return jdbc.sql("select distinct organization_id from camhub_all_camera_refs()").query(UUID.class).list();
    }

    public Optional<UUID> organizationOf(UUID cameraId) {
        return jdbc.sql("select camhub_camera_org(?)").param(cameraId).query(UUID.class).optional();
    }

    public record PathOwner(UUID cameraId, UUID organizationId) {
    }

    public Optional<PathOwner> byMediaPath(String path) {
        return jdbc.sql("select camera_id, organization_id from camhub_camera_by_path(?)").param(path)
                .query((rs, i) -> new PathOwner(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class))).optional();
    }
}
