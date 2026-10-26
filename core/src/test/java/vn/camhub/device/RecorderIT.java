package vn.camhub.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.camhub.ApiTestSupport;
import vn.camhub.device.api.DeviceControlPort.Capabilities;
import vn.camhub.device.api.DeviceControlPort.DeviceException;
import vn.camhub.device.api.DeviceControlPort.DeviceInfo;
import vn.camhub.device.api.DeviceControlPort.RecorderChannel;
import vn.camhub.device.api.DeviceControlPort.RecorderInfo;
import vn.camhub.device.api.DeviceControlPort.Resolution;
import vn.camhub.device.api.DeviceControlPort.StreamProfile;
import vn.camhub.device.application.CameraStatusService;
import vn.camhub.platform.crypto.CryptoService;
import vn.camhub.platform.tenancy.OrganizationContext;

/** US-03 Thêm đầu ghi NVR/DVR, US-28 Đổi mật khẩu, khởi động lại camera. */
class RecorderIT extends ApiTestSupport {

    @Autowired
    CameraStatusService status;
    @Autowired
    CryptoService crypto;

    UUID site;
    UUID zone;
    String suffix;

    @BeforeEach
    void site() throws Exception {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        site = idOf(postJson("/api/v1/sites", Map.of("name", "Kho " + suffix), admin()));
        zone = idOf(postJson("/api/v1/sites/" + site + "/zones", Map.of("name", "Kho A"), admin()));
    }

    static RecorderInfo nvr16() {
        List<RecorderChannel> ch = new ArrayList<>();
        for (int i = 1; i <= 16; i++) {
            boolean signal = i <= 3;
            ch.add(new RecorderChannel(i, String.format("Kênh %02d – %s", i, i == 1 ? "Cổng" : "Kho"), "vs" + i,
                    "p" + i + "01", "p" + i + "02", signal, new Resolution(1920, 1080),
                    "rtsp://10.0.0.5:554/Streaming/Channels/" + i + "01",
                    "rtsp://10.0.0.5:554/Streaming/Channels/" + i + "02"));
        }
        return new RecorderInfo("Hikvision", "DS-7616NI-K2", 16, ch);
    }

    @Test
    void dauGhi16Kenh_chon3Kenh_tao3CameraTenTheoKenh_matKetNoiThiCa3Offline() throws Exception {
        when(devices.recorderChannels(any())).thenReturn(nvr16());
        Map<String, Object> conn = Map.of("host", "10.0.0.5", "port", 80, "username", "admin", "password", "pw");
        JsonNode info = json.readTree(postJson("/api/v1/recorders/channels", Map.of("siteId", site, "connection", conn),
                admin()).getResponse().getContentAsString());
        assertThat(info.get("channelCount").asInt()).isEqualTo(16);

        // Kênh không có tín hiệu bị từ chối
        assertThat(postJson("/api/v1/recorders", Map.of("zoneId", zone, "connection", conn, "channels",
                List.of(Map.of("channel", 9))), admin()).getResponse().getContentAsString()).contains("CHANNEL_NO_SIGNAL");

        JsonNode created = json.readTree(postJson("/api/v1/recorders", Map.of("zoneId", zone, "name", "NVR " + suffix,
                "connection", conn, "channels", List.of(Map.of("channel", 1), Map.of("channel", 2),
                        Map.of("channel", 3))), admin()).getResponse().getContentAsString());
        UUID recorder = UUID.fromString(created.get("recorderId").asText());
        assertThat(created.get("cameraIds")).hasSize(3);
        List<String> names = OrganizationContext.callAs(orgId(), () -> jdbc.sql(
                "select name from camera where recorder_id = ? order by channel_no").param(recorder)
                .query(String.class).list());
        assertThat(names).containsExactly("Kênh 01 – Cổng", "Kênh 02 – Kho", "Kênh 03 – Kho");
        verify(mediamtx).upsertPath(any(), argThat(p -> p.source().endsWith("10.0.0.5:554/Streaming/Channels/301")));

        // AC2: đầu ghi mất kết nối → cả 3 camera mất kết nối
        status.apply(recorder, "OFFLINE", Instant.now(), "test");
        List<String> statuses = OrganizationContext.callAs(orgId(), () -> jdbc.sql(
                "select status from camera where recorder_id = ?").param(recorder).query(String.class).list());
        assertThat(statuses).hasSize(3).allMatch("OFFLINE"::equals);
    }

    UUID onvifCamera(String name, String host) throws Exception {
        when(devices.describe(any())).thenReturn(new DeviceInfo("Hik", "DS", "1", "SN", null,
                new Capabilities(false, false, false, true, true, false, false, false),
                List.of(new StreamProfile("p1", "main", "MAIN", "H264", 1920, 1080, 25, 4096,
                        "rtsp://" + host + ":554/1", false))));
        return idOf(postJson("/api/v1/cameras", Map.of("zoneId", zone, "name", name, "connection", Map.of(
                "protocol", "ONVIF", "host", host, "port", 80, "username", "admin", "password", "old-pass")), admin()));
    }

    String storedPassword(UUID cam) {
        return OrganizationContext.callAs(orgId(), () -> crypto.decrypt(jdbc.sql(
                "select password_enc from camera where id = ?").param(cam).query(String.class).single()));
    }

    @Test
    void doiMatKhau4Camera_thanhCongThiLuuMoi_thatBaiGiuCuVaBaoLoi() throws Exception {
        List<UUID> cams = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            cams.add(onvifCamera("Kho số " + (i + 4) + " " + suffix, "10.1.1." + (10 + i)));
        }
        doThrow(new DeviceException("AUTH_FAILED", "sai")).when(devices)
                .changePassword(argThat(a -> a != null && "10.1.1.12".equals(a.host())), any(), any());
        JsonNode r = json.readTree(postJson("/api/v1/cameras/password", Map.of("cameraIds", cams,
                "newPassword", "New-Pass-2026"), admin()).getResponse().getContentAsString());
        assertThat(r).hasSize(4);
        for (int i = 0; i < 4; i++) {
            boolean ok = r.get(i).get("ok").asBoolean();
            assertThat(ok).isEqualTo(i != 2);
            assertThat(storedPassword(cams.get(i))).isEqualTo(i != 2 ? "New-Pass-2026" : "old-pass");
        }
        assertThat(r.get(2).get("code").asText()).isEqualTo("AUTH_FAILED");
        assertThat(OrganizationContext.callAs(orgId(), () -> jdbc.sql(
                "select count(*) from audit_log where action = 'CHANGE_CAMERA_PASSWORD' and target_id = any(?)")
                .param(cams.stream().map(UUID::toString).toArray(String[]::new)).query(Long.class).single()))
                .isEqualTo(4);
    }

    @Test
    void khoiDongLai_theoDoiDenKhiTrucTuyenLai() throws Exception {
        UUID cam = onvifCamera("Kho số 7 " + suffix, "10.1.2.7");
        JsonNode r = json.readTree(postJson("/api/v1/cameras/" + cam + "/reboot", Map.of(), admin())
                .getResponse().getContentAsString());
        assertThat(r.get("state").asText()).isEqualTo("REBOOTING");
        verify(devices).reboot(argThat(a -> "10.1.2.7".equals(a.host())));
        assertThat(getJson("/api/v1/cameras/" + cam + "/reboot", admin()).get("state").asText()).isEqualTo("REBOOTING");
        status.apply(cam, "OFFLINE", Instant.now().minusSeconds(5), "reboot");
        status.apply(cam, "ONLINE", Instant.now(), "back");
        assertThat(getJson("/api/v1/cameras/" + cam + "/reboot", admin()).get("state").asText()).isEqualTo("ONLINE");
        assertThat(OrganizationContext.callAs(orgId(), () -> jdbc.sql(
                "select count(*) from audit_log where action = 'REBOOT_CAMERA' and target_id = ?")
                .param(cam.toString()).query(Long.class).single())).isEqualTo(1);
        verify(devices).reboot(eq(new vn.camhub.device.api.DeviceControlPort.DeviceAddress("10.1.2.7", 80, "admin",
                "old-pass")));
    }
}
