package vn.camhub.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import vn.camhub.ApiTestSupport;
import vn.camhub.device.api.DeviceControlPort.Capabilities;
import vn.camhub.device.api.DeviceControlPort.DeviceInfo;
import vn.camhub.device.api.DeviceControlPort.NetworkInfo;
import vn.camhub.device.api.DeviceControlPort.NetworkSetResult;
import vn.camhub.device.api.DeviceControlPort.StreamProfile;
import vn.camhub.platform.tenancy.OrganizationContext;

/** US-25 Cấu hình mạng và thời gian. */
class NetworkConfigIT extends ApiTestSupport {

    @Test
    void doiIp_capNhatDiaChiDaLuu_vaDanhDauLechGioQua5Giay() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID site = idOf(postJson("/api/v1/sites", Map.of("name", "Mạng " + suffix), admin()));
        UUID zone = idOf(postJson("/api/v1/sites/" + site + "/zones", Map.of("name", "Kho"), admin()));
        String oldIp = "10.55." + (int) (Math.random() * 200) + ".10";
        when(devices.describe(any())).thenReturn(new DeviceInfo("Hik", "DS", "1", "SN", null,
                new Capabilities(false, false, false, true, true, false, false, false),
                List.of(new StreamProfile("p1", "main", "MAIN", "H264", 1920, 1080, 25, 4096,
                        "rtsp://" + oldIp + ":554/Streaming/101", false))));
        UUID cam = idOf(postJson("/api/v1/cameras", Map.of("zoneId", zone, "name", "Lệch giờ " + suffix, "connection",
                Map.of("protocol", "ONVIF", "host", oldIp, "port", 80, "username", "u", "password", "p")), admin()));

        // AC2: camera lệch 7 giây → danh sách đánh dấu
        when(devices.network(any())).thenReturn(new NetworkInfo(oldIp, 24, "10.55.0.1", List.of(), "aa", "cam",
                List.of("pool.ntp.org"), false, false, "NTP", null, "2026-10-26T10:00:07Z", 7.0));
        assertThat(getJson("/api/v1/cameras/" + cam + "/config/network", admin()).get("clockDriftSeconds").asDouble())
                .isEqualTo(7.0);
        JsonNode list = getJson("/api/v1/cameras?q=" + suffix, admin());
        assertThat(list.get("items").get(0).get("clockDriftSeconds").asDouble()).isEqualTo(7.0);

        // IP sai định dạng bị từ chối
        var bad = mvc.perform(authed(put("/api/v1/cameras/" + cam + "/config/network"), admin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"dhcp\":false,\"address\":\"10.1.1\",\"prefixLength\":24}"))
                .andReturn();
        assertThat(bad.getResponse().getContentAsString()).contains("INVALID_NETWORK");

        // AC1: đổi thành công → địa chỉ và URL luồng đã lưu chuyển sang IP mới
        String newIp = "10.56." + (int) (Math.random() * 200) + ".20";
        when(devices.setNetwork(any(), argThat(c -> newIp.equals(c.address())))).thenReturn(new NetworkSetResult(false,
                newIp));
        var ok = mvc.perform(authed(put("/api/v1/cameras/" + cam + "/config/network"), admin())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("dhcp", false,
                        "address", newIp, "prefixLength", 24, "gateway", "10.56.0.1")))).andReturn();
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(getJson("/api/v1/cameras/" + cam, admin()).get("ipAddress").asText()).isEqualTo(newIp);
        String url = OrganizationContext.callAs(orgId(), () -> jdbc.sql(
                "select rtsp_url from camera_stream where camera_id = ? and stream_type = 'MAIN'").param(cam)
                .query(String.class).single());
        assertThat(url).isEqualTo("rtsp://" + newIp + ":554/Streaming/101");
    }
}
