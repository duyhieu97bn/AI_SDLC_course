package vn.camhub.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import vn.camhub.ApiTestSupport;
import vn.camhub.device.api.DeviceControlPort.Capabilities;
import vn.camhub.device.api.DeviceControlPort.DeviceException;
import vn.camhub.device.api.DeviceControlPort.DeviceInfo;
import vn.camhub.device.api.DeviceControlPort.DiscoveredDevice;
import vn.camhub.device.api.DeviceControlPort.StreamProfile;
import vn.camhub.platform.messaging.MessagingTopology;
import vn.camhub.platform.tenancy.OrganizationContext;

/** US-01 Dò tìm, US-02 Thêm thủ công, US-04 Trạng thái camera. */
class DeviceIT extends ApiTestSupport {

    @Autowired
    RabbitTemplate rabbit;

    UUID site;
    UUID zone;
    String suffix;

    static DeviceInfo info(String mac) {
        return new DeviceInfo("Hikvision", "DS-2CD2143G2-I", "V5.7.3", "SN1", mac,
                new Capabilities(true, true, false, true, true, true, true, true),
                List.of(new StreamProfile("p1", "main", "MAIN", "H264", 2688, 1520, 25, 4096,
                                "rtsp://10.20.1.11:554/Streaming/Channels/101", false),
                        new StreamProfile("p2", "sub", "SUB", "H264", 640, 360, 15, 512,
                                "rtsp://10.20.1.11:554/Streaming/Channels/102", false)));
    }

    @BeforeEach
    void site() throws Exception {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        site = idOf(postJson("/api/v1/sites", Map.of("name", "Kho số 7 " + suffix), admin()));
        zone = idOf(postJson("/api/v1/sites/" + site + "/zones", Map.of("name", "Kho"), admin()));
    }

    @Test
    void themCameraOnvif_matKhauMaHoa_taoPathMediaMtx() throws Exception {
        when(devices.describe(any())).thenReturn(info("AA:BB:CC:00:00:" + suffix.substring(0, 2)));
        MvcResult r = postJson("/api/v1/cameras", Map.of("zoneId", zone, "name", "Cổng " + suffix,
                "connection", Map.of("protocol", "ONVIF", "host", "10.20.1.11", "port", 80, "username", "admin",
                        "password", "Mat-khau-camera!")), admin());
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        JsonNode cam = json.readTree(r.getResponse().getContentAsString());
        UUID id = UUID.fromString(cam.get("id").asText());
        assertThat(cam.get("verified").asBoolean()).isTrue();
        assertThat(cam.get("capabilities").get("ptz").asBoolean()).isTrue();
        assertThat(cam.has("password")).isFalse();

        String enc = OrganizationContext.callAs(orgId(), () -> jdbc.sql("select password_enc from camera where id = ?")
                .param(id).query(String.class).single());
        assertThat(enc).startsWith("v1:").doesNotContain("Mat-khau");
        assertThat(getJson("/api/v1/cameras/" + id + "/streams", admin())).hasSize(2);
        // MediaMTX nhận URL có tài khoản (mã hóa URL), trình duyệt thì không bao giờ thấy
        verify(mediamtx).upsertPath(argThat(p -> p.startsWith("cam-") && !p.endsWith("-sub")),
                argThat(c -> c.source().equals("rtsp://admin:Mat-khau-camera%21@10.20.1.11:554/Streaming/Channels/101")));
        assertThat(getJson("/api/v1/cameras/" + id + "/location-history", admin())).hasSize(1);
    }

    @Test
    void kiemTraKetNoiThatBai_chiLuuKhiChonLuuDuChuaKetNoi() throws Exception {
        when(devices.describe(any())).thenThrow(new DeviceException("UNREACHABLE", "timeout"));
        Map<String, Object> conn = Map.of("protocol", "ONVIF", "host", "10.99.0.1", "port", 80);
        JsonNode test = json.readTree(postJson("/api/v1/cameras/test-connection", conn, admin()).getResponse()
                .getContentAsString());
        assertThat(test.get("ok").asBoolean()).isFalse();
        assertThat(test.get("message").asText()).isEqualTo("Không tới được địa chỉ");

        MvcResult refused = postJson("/api/v1/cameras", Map.of("zoneId", zone, "connection", conn), admin());
        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        MvcResult saved = postJson("/api/v1/cameras", Map.of("zoneId", zone, "name", "Chưa kết nối " + suffix,
                "connection", conn, "saveUnverified", true), admin());
        assertThat(saved.getResponse().getStatus()).isEqualTo(201);
        assertThat(json.readTree(saved.getResponse().getContentAsString()).get("verified").asBoolean()).isFalse();
    }

    @Test
    void doTim_danhDauDaThem_themHangLoatMotCameraSaiMatKhau() throws Exception {
        String knownMac = "11:22:33:44:55:" + suffix.substring(0, 2);
        when(devices.describe(argThat(a -> a != null && "10.30.0.1".equals(a.host())))).thenReturn(info(knownMac));
        postJson("/api/v1/cameras", Map.of("zoneId", zone, "connection", Map.of("protocol", "ONVIF", "host", "10.30.0.1",
                "port", 80, "username", "u", "password", "p")), admin());

        when(devices.discover(any(), eq(30))).thenReturn(List.of(
                new DiscoveredDevice("10.30.0.1", 80, "Hikvision", "DS", knownMac, "DS", List.of()),
                new DiscoveredDevice("10.30.0.2", 80, "Dahua", "IPC", "aa-aa-aa-aa-aa-02", "IPC", List.of()),
                new DiscoveredDevice("10.30.0.3", 80, "Axis", "M30", "aa-aa-aa-aa-aa-03", "M30", List.of())));
        JsonNode found = json.readTree(postJson("/api/v1/sites/" + site + "/discovery", Map.of(), admin()).getResponse()
                .getContentAsString());
        assertThat(found).hasSize(3);
        assertThat(found.get(0).get("alreadyAdded").asBoolean()).isTrue();
        assertThat(found.get(1).get("alreadyAdded").asBoolean()).isFalse();

        when(devices.describe(argThat(a -> a != null && "10.30.0.2".equals(a.host())))).thenReturn(info("aa:aa:aa:aa:aa:02"));
        when(devices.describe(argThat(a -> a != null && "10.30.0.3".equals(a.host()))))
                .thenThrow(new DeviceException("AUTH_FAILED", "NotAuthorized"));
        JsonNode results = json.readTree(postJson("/api/v1/cameras/bulk", Map.of("zoneId", zone, "username", "admin",
                "password", "chung", "devices", List.of(Map.of("host", "10.30.0.2", "port", 80),
                        Map.of("host", "10.30.0.3", "port", 80))), admin()).getResponse().getContentAsString());
        assertThat(results.get(0).get("ok").asBoolean()).isTrue();
        assertThat(results.get(1).get("ok").asBoolean()).isFalse();
        assertThat(results.get(1).get("message").asText()).isEqualTo("Sai tên đăng nhập hoặc mật khẩu");
    }

    @Test
    void trangThaiCapNhatTuSuKienThietBi_khongXuLyLap() throws Exception {
        UUID cam = insertCamera(site, zone, "Kho số 7 cam " + suffix);
        String msgId = UUID.randomUUID().toString();
        Instant offlineAt = Instant.now().minusSeconds(5);
        send("device.offline", msgId, cam, offlineAt);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(getJson("/api/v1/cameras/" + cam, admin()).get("status").asText()).isEqualTo("OFFLINE"));
        JsonNode c = getJson("/api/v1/cameras/" + cam, admin());
        // PostgreSQL lưu đến micro giây
        assertThat(Instant.parse(c.get("lastOfflineAt").asText()))
                .isCloseTo(offlineAt, org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MILLIS));

        // Cùng messageId gửi lại (RabbitMQ giao lặp) dù nội dung khác: bị bỏ qua
        send("device.online", msgId, cam, Instant.now());
        Thread.sleep(1500);
        assertThat(getJson("/api/v1/cameras/" + cam, admin()).get("status").asText()).isEqualTo("OFFLINE");

        send("device.online", UUID.randomUUID().toString(), cam, Instant.now());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(getJson("/api/v1/cameras/" + cam, admin()).get("status").asText()).isEqualTo("ONLINE"));
    }

    private void send(String routingKey, String messageId, UUID camera, Instant at) throws Exception {
        MessageProperties p = new MessageProperties();
        p.setMessageId(messageId);
        p.setContentType("application/json");
        byte[] body = json.writeValueAsString(Map.of("cameraId", camera.toString(), "occurredAt", at.toString(),
                "reason", "test", "source", "MONITOR")).getBytes(StandardCharsets.UTF_8);
        rabbit.send(MessagingTopology.DEVICE_EVENTS, routingKey, new Message(body, p));
    }
}
