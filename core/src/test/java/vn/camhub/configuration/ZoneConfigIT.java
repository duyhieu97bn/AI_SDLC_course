package vn.camhub.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.ArrayList;
import java.util.Collections;
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
import vn.camhub.device.api.DeviceControlPort.Grid;
import vn.camhub.device.api.DeviceControlPort.MotionZones;
import vn.camhub.device.api.DeviceControlPort.PrivacyMasks;
import vn.camhub.device.api.DeviceControlPort.StreamProfile;
import vn.camhub.device.api.DeviceControlPort.ZonesConfig;

/** US-26 Vùng chuyển động và vùng che riêng tư: giới hạn số vùng theo khả năng camera. */
class ZoneConfigIT extends ApiTestSupport {

    UUID cam;

    @BeforeEach
    void camera() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID site = idOf(postJson("/api/v1/sites", Map.of("name", "Vùng " + suffix), admin()));
        UUID zone = idOf(postJson("/api/v1/sites/" + site + "/zones", Map.of("name", "Kho"), admin()));
        when(devices.describe(any())).thenReturn(new DeviceInfo("Hik", "DS", "1", "SN", null,
                new Capabilities(false, false, false, true, true, false, true, true),
                List.of(new StreamProfile("p1", "main", "MAIN", "H264", 1920, 1080, 25, 4096, "rtsp://x/1", false))));
        cam = idOf(postJson("/api/v1/cameras", Map.of("zoneId", zone, "connection", Map.of("protocol", "ONVIF",
                "host", "10.9.8.7", "port", 80, "username", "u", "password", "p")), admin()));
        List<List<Boolean>> cells = new ArrayList<>();
        for (int r = 0; r < 18; r++) {
            cells.add(new ArrayList<>(Collections.nCopies(22, true)));
        }
        when(devices.zones(any())).thenReturn(new ZonesConfig(
                new MotionZones(true, "CELL", new Grid(22, 18), cells, List.of(), 50, 4),
                new PrivacyMasks(true, 4, 8, List.of())));
    }

    MvcResult putJson(String url, Object body) throws Exception {
        return mvc.perform(authed(put(url), admin()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andReturn();
    }

    static List<Map<String, Double>> square(double x) {
        return List.of(Map.of("x", x, "y", 0.1), Map.of("x", x + 0.1, "y", 0.1), Map.of("x", x + 0.1, "y", 0.3));
    }

    @Test
    void docCauHinhVung_kemGioiHanCamera() throws Exception {
        var z = getJson("/api/v1/cameras/" + cam + "/config/zones", admin());
        assertThat(z.get("motion").get("grid").get("columns").asInt()).isEqualTo(22);
        assertThat(z.get("masks").get("maxMasks").asInt()).isEqualTo(4);
    }

    @Test
    void daGiac_vuotSoVungToiDa_tuChoiKhongGuiXuongCamera() throws Exception {
        List<Object> five = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            five.add(square(i * 0.15));
        }
        MvcResult r = putJson("/api/v1/cameras/" + cam + "/config/zones/motion",
                Map.of("mode", "POLYGON", "polygons", five, "sensitivity", 60));
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(r.getResponse().getContentAsString()).contains("LIMIT_EXCEEDED");
        verify(devices, never()).setMotionZones(any(), any());

        r = putJson("/api/v1/cameras/" + cam + "/config/zones/motion",
                Map.of("mode", "POLYGON", "polygons", List.of(square(0.1), square(0.5)), "sensitivity", 60));
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        verify(devices).setMotionZones(any(), argThat(c -> c.polygons().size() == 2 && c.sensitivity() == 60));
    }

    @Test
    void luoiO_saiKichThuoc_tuChoi() throws Exception {
        MvcResult r = putJson("/api/v1/cameras/" + cam + "/config/zones/motion",
                Map.of("mode", "CELL", "cells", List.of(List.of(true, false))));
        assertThat(r.getResponse().getContentAsString()).contains("INVALID_ZONE");
    }

    @Test
    void vungChe_quaSoDiemHoacSoVung_tuChoi_hopLeThiApDung() throws Exception {
        List<Map<String, Double>> nine = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            nine.add(Map.of("x", 0.05 * i, "y", 0.05 * (i % 2)));
        }
        MvcResult r = putJson("/api/v1/cameras/" + cam + "/config/zones/masks",
                Map.of("items", List.of(Map.of("points", nine))));
        assertThat(r.getResponse().getContentAsString()).contains("LIMIT_EXCEEDED");

        r = putJson("/api/v1/cameras/" + cam + "/config/zones/masks",
                Map.of("items", List.of(Map.of("points", square(0.2)), Map.of("points", square(0.6)))));
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        verify(devices).setPrivacyMasks(any(), argThat(m -> m.size() == 2));
    }
}
