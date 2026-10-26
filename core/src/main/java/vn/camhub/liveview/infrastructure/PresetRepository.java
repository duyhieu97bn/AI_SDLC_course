package vn.camhub.liveview.infrastructure;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.camhub.platform.tenancy.OrganizationContext;

/** Preset PTZ lưu từ CamHub (source CAMHUB); preset có sẵn trên camera đọc trực tiếp từ thiết bị. */
@Repository
public class PresetRepository {

    private final JdbcClient jdbc;

    public PresetRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** token thiết bị → tên */
    public Map<String, String> tokens(UUID cameraId) {
        Map<String, String> m = new HashMap<>();
        jdbc.sql("select device_token, name from ptz_preset where camera_id = ?").param(cameraId)
                .query((rs, i) -> m.put(rs.getString(1), rs.getString(2))).list();
        return m;
    }

    public void insert(UUID cameraId, String name, String token, UUID by) {
        jdbc.sql("""
                insert into ptz_preset (organization_id, camera_id, name, device_token, source, created_by)
                values (?, ?, ?, ?, 'CAMHUB', ?)
                on conflict (camera_id, device_token) do update set name = excluded.name
                """).params(OrganizationContext.require(), cameraId, name, token, by).update();
    }

    public void delete(UUID cameraId, String token) {
        jdbc.sql("delete from ptz_preset where camera_id = ? and device_token = ?").params(cameraId, token).update();
    }
}
