package vn.camhub.liveview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import vn.camhub.ApiTestSupport;
import vn.camhub.device.api.DeviceControlPort.Capabilities;
import vn.camhub.device.api.DeviceControlPort.DeviceInfo;
import vn.camhub.device.api.DeviceControlPort.StreamProfile;

/** US-08 Xem trực tiếp, US-10 Bố cục, US-12 PTZ (tranh chấp điều khiển), US-13 Preset. */
class LiveIT extends ApiTestSupport {

    UUID site;
    UUID zone;
    String suffix;

    @BeforeEach
    void site() throws Exception {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        site = idOf(postJson("/api/v1/sites", Map.of("name", "Bãi xe " + suffix), admin()));
        zone = idOf(postJson("/api/v1/sites/" + site + "/zones", Map.of("name", "Bãi"), admin()));
    }

    UUID onvifCamera(boolean ptz, String mainCodec, boolean bFrames) throws Exception {
        when(devices.describe(any())).thenReturn(new DeviceInfo("Axis", "Q6135", "10.12", "SN", null,
                new Capabilities(ptz, false, false, true, true, false, true, false),
                List.of(new StreamProfile("p1", "main", "MAIN", mainCodec, 1920, 1080, 25, 4096,
                                "rtsp://10.1.1.1/main", bFrames),
                        new StreamProfile("p2", "sub", "SUB", "H264", 640, 360, 15, 512, "rtsp://10.1.1.1/sub", false))));
        return idOf(postJson("/api/v1/cameras", Map.of("zoneId", zone, "name", "PTZ " + suffix,
                "connection", Map.of("protocol", "ONVIF", "host", "10.1.1.1", "port", 80, "username", "u",
                        "password", "p")), admin()));
    }

    @Test
    void phienXem_luongPhu_vaChuyenMaKhiH265() throws Exception {
        UUID cam = onvifCamera(false, "H265", false);
        JsonNode main = json.readTree(postJson("/api/v1/cameras/" + cam + "/live-session", Map.of("stream", "MAIN"),
                admin()).getResponse().getContentAsString());
        assertThat(main.get("transcoded").asBoolean()).isTrue();
        assertThat(main.get("whepUrl").asText()).matches("/media/webrtc/cam-[0-9a-f]{32}_h264/whep\\?token=.+");
        assertThat(main.toString()).doesNotContain("10.1.1.1"); // không lộ URL camera

        JsonNode sub = json.readTree(postJson("/api/v1/cameras/" + cam + "/live-session", Map.of("stream", "SUB"),
                admin()).getResponse().getContentAsString());
        assertThat(sub.get("stream").asText()).isEqualTo("SUB");
        assertThat(sub.get("transcoded").asBoolean()).isFalse();
        assertThat(sub.get("whepUrl").asText()).contains("-sub/whep");
    }

    @Test
    void khongCoQuyenThi403() throws Exception {
        UUID cam = onvifCamera(false, "H264", false);
        UUID other = idOf(postJson("/api/v1/sites", Map.of("name", "Khác " + suffix), admin()));
        createUser(admin(), "viewer-live-" + suffix + "@camhub.test", "VIEWER", "SITE", other);
        Session v = login("viewer-live-" + suffix + "@camhub.test", "User-Pass-2026");
        assertThat(postJson("/api/v1/cameras/" + cam + "/live-session", Map.of(), v).getResponse().getStatus())
                .isEqualTo(403);
    }

    @Test
    void ptzTranhChapDieuKhien_rankCaoHonGiuKhoa() throws Exception {
        UUID cam = onvifCamera(true, "H264", false);
        createUser(admin(), "huong-ptz-" + suffix + "@camhub.test", "SITE_MANAGER", "SITE", site);
        createUser(admin(), "lan-ptz-" + suffix + "@camhub.test", "OPERATOR", "SITE", site);
        Session huong = login("huong-ptz-" + suffix + "@camhub.test", "User-Pass-2026");
        Session lan = login("lan-ptz-" + suffix + "@camhub.test", "User-Pass-2026");

        assertThat(postJson("/api/v1/cameras/" + cam + "/ptz", Map.of("pan", 0.5, "tilt", 0, "zoom", 0), huong)
                .getResponse().getStatus()).isEqualTo(204);
        MvcResult denied = postJson("/api/v1/cameras/" + cam + "/ptz", Map.of("pan", -0.5, "tilt", 0, "zoom", 0), lan);
        assertThat(denied.getResponse().getStatus()).isEqualTo(409);
        assertThat(denied.getResponse().getContentAsString()).contains("PTZ_LOCKED").contains("huong-ptz-" + suffix);
        verify(devices).ptzMove(any(), eq("p1"), eq(0.5), anyDouble(), anyDouble(), anyInt());

        // Admin (rank cao nhất) giành được khóa
        assertThat(postJson("/api/v1/cameras/" + cam + "/ptz/stop", Map.of(), admin()).getResponse().getStatus())
                .isEqualTo(204);
    }

    @Test
    void cameraKhongPtzThiTuChoi() throws Exception {
        UUID cam = onvifCamera(false, "H264", false);
        MvcResult r = postJson("/api/v1/cameras/" + cam + "/ptz", Map.of("pan", 0.5, "tilt", 0, "zoom", 0), admin());
        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(r.getResponse().getContentAsString()).contains("PTZ_NOT_SUPPORTED");
    }

    @Test
    void luuVaGoiPreset() throws Exception {
        UUID cam = onvifCamera(true, "H264", false);
        when(devices.setPreset(any(), eq("p1"), eq("Bãi xe"))).thenReturn("PRESET_7");
        when(devices.presets(any(), eq("p1"))).thenReturn(List.of(
                new vn.camhub.device.api.DeviceControlPort.Preset("PRESET_7", "Bãi xe"),
                new vn.camhub.device.api.DeviceControlPort.Preset("1", "Home")));
        assertThat(postJson("/api/v1/cameras/" + cam + "/presets", Map.of("name", "Bãi xe"), admin()).getResponse()
                .getStatus()).isEqualTo(201);
        JsonNode list = getJson("/api/v1/cameras/" + cam + "/presets", admin());
        assertThat(list.get(0).get("name").asText()).isEqualTo("Bãi xe");
        assertThat(list.get(0).get("source").asText()).isEqualTo("CAMHUB");
        assertThat(list.get(1).get("source").asText()).isEqualTo("DEVICE");
        postJson("/api/v1/cameras/" + cam + "/presets/PRESET_7/goto", Map.of(), admin());
        verify(devices).gotoPreset(any(), eq("p1"), eq("PRESET_7"));
    }

    @Test
    void boCuc_luuMoLai_vaCameraMatQuyenBiLoai() throws Exception {
        UUID cam1 = insertCamera(site, zone, "Kho số 7 " + suffix);
        UUID other = idOf(postJson("/api/v1/sites", Map.of("name", "KhoKhac " + suffix), admin()));
        UUID otherZone = idOf(postJson("/api/v1/sites/" + other + "/zones", Map.of("name", "Z"), admin()));
        UUID cam2 = insertCamera(other, otherZone, "Cổng khác " + suffix);
        UUID lanId = createUser(admin(), "lan-layout-" + suffix + "@camhub.test", "OPERATOR", "SITE", site);
        Map<String, Object> extra = Map.of("roleId", roleId("OPERATOR"), "scopeType", "SITE", "scopeId", other);
        mvc.perform(authed(put("/api/v1/users/" + lanId + "/assignments"), admin()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(List.of(Map.of("roleId", roleId("OPERATOR"), "scopeType", "SITE",
                        "scopeId", site), extra))));
        Session lan = login("lan-layout-" + suffix + "@camhub.test", "User-Pass-2026");

        UUID layout = idOf(postJson("/api/v1/layouts", Map.of("name", "Ca đêm – Kho Bắc Ninh", "gridSize", 4,
                "tiles", List.of(Map.of("position", 0, "cameraId", cam1, "streamMode", "AUTO"),
                        Map.of("position", 1, "cameraId", cam2, "streamMode", "LOW"))), lan));
        assertThat(getJson("/api/v1/layouts", lan).get(0).get("id").asText()).isEqualTo(layout.toString());

        // Lưới 4 không chứa được 5 ô; lưới 6 không hợp lệ (INV-13)
        assertThat(postJson("/api/v1/layouts", Map.of("name", "Sai", "gridSize", 6, "tiles", List.of()), lan)
                .getResponse().getStatus()).isEqualTo(400);

        // Thu hồi quyền ở địa điểm thứ hai → camera đó biến khỏi bố cục đã lưu (US-10 AC2)
        mvc.perform(authed(put("/api/v1/users/" + lanId + "/assignments"), admin()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(List.of(Map.of("roleId", roleId("OPERATOR"), "scopeType", "SITE",
                        "scopeId", site)))));
        JsonNode tiles = getJson("/api/v1/layouts", lan).get(0).get("tiles");
        assertThat(tiles).hasSize(1);
        assertThat(tiles.get(0).get("cameraId").asText()).isEqualTo(cam1.toString());
        assertThat(mvc.perform(authed(get("/api/v1/layouts"), lan)).andReturn().getResponse().getStatus()).isEqualTo(200);
    }
}
