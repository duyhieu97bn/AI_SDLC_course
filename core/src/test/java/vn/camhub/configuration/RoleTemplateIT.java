package vn.camhub.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import vn.camhub.ApiTestSupport;
import vn.camhub.device.api.DeviceControlPort.Capabilities;
import vn.camhub.device.api.DeviceControlPort.DeviceException;
import vn.camhub.device.api.DeviceControlPort.DeviceInfo;
import vn.camhub.device.api.DeviceControlPort.EncoderConfig;
import vn.camhub.device.api.DeviceControlPort.EncoderOptions;
import vn.camhub.device.api.DeviceControlPort.ImagingOptions;
import vn.camhub.device.api.DeviceControlPort.ImagingSettings;
import vn.camhub.device.api.DeviceControlPort.IntRange;
import vn.camhub.device.api.DeviceControlPort.Range;
import vn.camhub.device.api.DeviceControlPort.Resolution;
import vn.camhub.device.api.DeviceControlPort.StreamProfile;

/** US-43 Vai trò tùy chỉnh, US-27 Cấu hình mẫu. */
class RoleTemplateIT extends ApiTestSupport {

    String suffix;
    UUID site;
    UUID zone;

    @BeforeEach
    void setUp() throws Exception {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        site = idOf(postJson("/api/v1/sites", Map.of("name", "Mẫu " + suffix), admin()));
        zone = idOf(postJson("/api/v1/sites/" + site + "/zones", Map.of("name", "Kho"), admin()));
    }

    JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    @Test
    void saoChepOperator_boViewPlayback_xoaKhiDangDungBiTuChoi_vaiTroHeThongKhongSuaDuoc() throws Exception {
        UUID operator = roleId("OPERATOR");
        JsonNode created = body(postJson("/api/v1/roles", Map.of("name", "Bảo vệ ca đêm " + suffix, "copyFrom", operator,
                "permissions", List.of("VIEW_LIVE", "CONTROL_PTZ", "TALK_AUDIO")), admin()));
        UUID role = UUID.fromString(created.get("id").asText());
        assertThat(created.get("system").asBoolean()).isFalse();
        assertThat(created.get("permissions").toString()).doesNotContain("VIEW_PLAYBACK").contains("VIEW_LIVE");

        // Gán cho 4 người → xóa bị từ chối kèm số người (INV-21)
        for (int i = 0; i < 4; i++) {
            Map<String, Object> a = new java.util.HashMap<>(Map.of("roleId", role, "scopeType", "SITE"));
            a.put("scopeId", site);
            postJson("/api/v1/users", Map.of("email", "bv" + i + "-" + suffix + "@camhub.test", "displayName", "BV " + i,
                    "password", "User-Pass-2026", "assignments", List.of(a)), admin());
        }
        MvcResult del = mvc.perform(authed(delete("/api/v1/roles/" + role), admin())).andReturn();
        assertThat(del.getResponse().getStatus()).isEqualTo(409);
        assertThat(del.getResponse().getContentAsString()).contains("ROLE_IN_USE").contains("4");

        // Vai trò hệ thống: không sửa, không xóa (INV-20)
        MvcResult edit = mvc.perform(authed(put("/api/v1/roles/" + operator), admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"X\",\"permissions\":[\"VIEW_LIVE\"]}")).andReturn();
        assertThat(edit.getResponse().getContentAsString()).contains("SYSTEM_ROLE");
        assertThat(mvc.perform(authed(delete("/api/v1/roles/" + operator), admin())).andReturn().getResponse()
                .getContentAsString()).contains("SYSTEM_ROLE");

        // Người không có MANAGE_ROLES không tạo được vai trò
        createUser(admin(), "sm-r-" + suffix + "@camhub.test", "SITE_MANAGER", "SITE", site);
        Session sm = login("sm-r-" + suffix + "@camhub.test", "User-Pass-2026");
        assertThat(postJson("/api/v1/roles", Map.of("name", "X " + suffix, "permissions", List.of("VIEW_LIVE")), sm)
                .getResponse().getStatus()).isEqualTo(403);
    }

    UUID camera(String name, String host, boolean wdr) throws Exception {
        when(devices.describe(argThat(a -> a != null && host.equals(a.host())))).thenReturn(new DeviceInfo("Hik", "DS",
                "1", "SN", null, new Capabilities(false, false, false, true, true, false, false, false),
                List.of(new StreamProfile("p1", "main", "MAIN", "H264", 1920, 1080, 25, 4096, "rtsp://" + host + "/1", false))));
        when(devices.imaging(argThat(a -> a != null && host.equals(a.host())))).thenReturn(new ImagingSettings("vs0", 50.0,
                50.0, "AUTO", false, new ImagingOptions(new Range(0, 100), new Range(0, 100), List.of("AUTO", "NIGHT"),
                        wdr)));
        when(devices.encoders(argThat(a -> a != null && host.equals(a.host())))).thenReturn(List.of(new EncoderConfig(
                "enc1", "Main", "H264", 1920, 1080, 25, 4096, 50, false, new EncoderOptions(List.of("H264"),
                        List.of(new Resolution(1920, 1080)), new IntRange(1, 25), new IntRange(256, 8192),
                        new IntRange(10, 100)))));
        return idOf(postJson("/api/v1/cameras", Map.of("zoneId", zone, "name", name, "connection", Map.of("protocol",
                "ONVIF", "host", host, "port", 80, "username", "u", "password", "p")), admin()));
    }

    @Test
    void apMau4Camera_2ThanhCong_1MotPhanBoQuaWdr_1ThatBai_coApLai() throws Exception {
        String base = "10.66." + (int) (Math.random() * 200) + ".";
        List<UUID> cams = new ArrayList<>();
        cams.add(camera("K1 " + suffix, base + "1", true));
        cams.add(camera("K2 " + suffix, base + "2", true));
        cams.add(camera("K3 không WDR " + suffix, base + "3", false));
        cams.add(camera("K4 mất kết nối " + suffix, base + "4", true));
        when(devices.encoders(argThat(a -> a != null && (base + "4").equals(a.host()))))
                .thenThrow(new DeviceException("UNREACHABLE", "Không kết nối được thiết bị"));

        JsonNode t = body(postJson("/api/v1/config-templates", Map.of("name", "Kho – ghi theo sự kiện " + suffix,
                "recording", Map.of("mode", "EVENT", "schedule", List.of(), "preEventSeconds", 10, "postEventSeconds", 30),
                "stream", Map.of("fps", 15, "bitrateKbps", 2048),
                "image", Map.of("wdr", true, "irMode", "NIGHT")), admin()));
        JsonNode app = body(postJson("/api/v1/config-templates/" + t.get("id").asText() + "/apply",
                Map.of("cameraIds", cams), admin()));
        Map<String, String> results = new java.util.HashMap<>();
        app.get("results").forEach(r -> results.put(r.get("cameraName").asText(), r.get("result").asText()));
        assertThat(results.get("K1 " + suffix)).isEqualTo("SUCCESS");
        assertThat(results.get("K2 " + suffix)).isEqualTo("SUCCESS");
        assertThat(results.get("K3 không WDR " + suffix)).isEqualTo("PARTIAL");
        assertThat(results.get("K4 mất kết nối " + suffix)).isEqualTo("FAILED");
        assertThat(app.get("results").toString()).contains("Camera không hỗ trợ WDR");

        // Áp lại: chỉ camera thất bại; camera đã kết nối lại thì thành công
        when(devices.encoders(argThat(a -> a != null && (base + "4").equals(a.host())))).thenReturn(List.of(
                new EncoderConfig("enc1", "Main", "H264", 1920, 1080, 25, 4096, 50, false, null)));
        JsonNode again = body(postJson("/api/v1/template-applications/" + app.get("id").asText() + "/reapply-failed",
                Map.of(), admin()));
        assertThat(again.get("results")).hasSize(1);
        assertThat(again.get("results").get(0).get("result").asText()).isEqualTo("SUCCESS");
    }
}
