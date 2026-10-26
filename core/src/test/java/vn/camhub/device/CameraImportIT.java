package vn.camhub.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import vn.camhub.ApiTestSupport;
import vn.camhub.device.api.DeviceControlPort.Capabilities;
import vn.camhub.device.api.DeviceControlPort.DeviceInfo;
import vn.camhub.device.api.DeviceControlPort.StreamProfile;

/** US-07 Nhập camera từ tệp: xem trước kèm lỗi, bỏ qua dòng lỗi. */
class CameraImportIT extends ApiTestSupport {

    @Test
    void xemTruocBaoLoiTungDong_nhapChiDongHopLe() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String site = "Kho nhập " + suffix;
        UUID siteId = idOf(postJson("/api/v1/sites", Map.of("name", site), admin()));
        postJson("/api/v1/sites/" + siteId + "/zones", Map.of("name", "Cổng"), admin());
        when(devices.describe(any())).thenReturn(new DeviceInfo("Hik", "DS", "1", "SN", null,
                new Capabilities(false, false, false, true, true, false, false, false),
                List.of(new StreamProfile("p1", "main", "MAIN", "H264", 1920, 1080, 25, 4096, "rtsp://x/1", false))));
        String csv = "﻿Tên;Địa điểm;Khu vực;Giao thức;Địa chỉ IP;Cổng;Tài khoản;Mật khẩu;URL RTSP\n"
                + "Cổng A " + suffix + ";" + site + ";Cổng;ONVIF;10.77.1.1;80;admin;pw;\n"
                + "Thiếu IP " + suffix + ";" + site + ";Cổng;ONVIF;;80;admin;pw;\n"
                + "Sai khu vực " + suffix + ";" + site + ";Không có;ONVIF;10.77.1.2;80;admin;pw;\n"
                + "\"Cổng B; ngoài\" " + suffix + ";" + site + ";Cổng;RTSP;;;admin;pw;rtsp://10.77.1.3:554/s1\n";
        MockMultipartFile file = new MockMultipartFile("file", "cameras.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));
        Session admin = admin();
        JsonNode preview = json.readTree(mvc.perform(authed(multipart("/api/v1/cameras/import/preview").file(file), admin))
                .andReturn().getResponse().getContentAsString());
        assertThat(preview.get("total").asInt()).isEqualTo(4);
        assertThat(preview.get("valid").asInt()).isEqualTo(2);
        assertThat(preview.get("rows").get(1).get("errors").toString()).contains("Thiếu địa chỉ IP");
        assertThat(preview.get("rows").get(2).get("errors").toString()).contains("Không tìm thấy khu vực");
        assertThat(preview.toString()).doesNotContain("\"pw\"");

        JsonNode results = json.readTree(mvc.perform(authed(multipart("/api/v1/cameras/import").file(file)
                .param("saveUnverified", "true"), admin)).andReturn().getResponse().getContentAsString());
        assertThat(results).hasSize(4);
        assertThat(results.get(0).get("ok").asBoolean()).isTrue();
        assertThat(results.get(1).get("ok").asBoolean()).isFalse();
        assertThat(results.get(2).get("ok").asBoolean()).isFalse();
        assertThat(results.get(3).get("ok").asBoolean()).isTrue();
        assertThat(getJson("/api/v1/cameras?q=" + suffix, admin).get("total").asInt()).isEqualTo(2);
    }
}
