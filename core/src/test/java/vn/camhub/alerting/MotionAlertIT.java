package vn.camhub.alerting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import vn.camhub.ApiTestSupport;
import vn.camhub.alerting.application.MotionRuleEvaluator;
import vn.camhub.device.api.DeviceControlPort.Capabilities;
import vn.camhub.device.api.DeviceControlPort.DeviceInfo;
import vn.camhub.device.api.DeviceControlPort.StreamProfile;
import vn.camhub.notification.application.NotificationDispatcher;
import vn.camhub.platform.messaging.MessagingTopology;
import vn.camhub.platform.tenancy.OrganizationContext;

/** US-36 Cảnh báo chuyển động đa kênh, US-37 Xử lý và chuyển giao cảnh báo. */
class MotionAlertIT extends ApiTestSupport {

    @Autowired
    MotionRuleEvaluator evaluator;
    @Autowired
    NotificationDispatcher dispatcher;
    @Autowired
    RabbitTemplate rabbit;

    String suffix;
    UUID site;
    UUID zone;
    UUID cam;
    UUID lan;

    @BeforeEach
    void setUp() throws Exception {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        site = idOf(postJson("/api/v1/sites", Map.of("name", "Kho BN " + suffix), admin()));
        zone = idOf(postJson("/api/v1/sites/" + site + "/zones", Map.of("name", "Cổng sau"), admin()));
        when(devices.describe(any())).thenReturn(new DeviceInfo("Hik", "DS", "1", "SN", null,
                new Capabilities(false, false, false, true, true, false, true, false),
                List.of(new StreamProfile("p1", "main", "MAIN", "H264", 1920, 1080, 25, 4096, "rtsp://10.9.1.1/1", false))));
        cam = idOf(postJson("/api/v1/cameras", Map.of("zoneId", zone, "name", "Cổng sau " + suffix, "connection",
                Map.of("protocol", "ONVIF", "host", "10.9.1." + (int) (Math.random() * 200 + 2), "port", 80,
                        "username", "u", "password", "p")), admin()));
        lan = createUser(admin(), "lan-m-" + suffix + "@camhub.test", "OPERATOR", "SITE", site);
        Session l = login("lan-m-" + suffix + "@camhub.test", "User-Pass-2026");
        mvc.perform(authed(put("/api/v1/me/channels/TELEGRAM"), l).contentType(MediaType.APPLICATION_JSON)
                .content("{\"address\":\"chat-lan-" + suffix + "\"}"));
        mvc.perform(authed(put("/api/v1/notification-channels/TELEGRAM"), admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"secret\":\"bot-token-1\"}"));
    }

    UUID rule(String from, String to, int cooldown) throws Exception {
        MvcResult r = postJson("/api/v1/motion-rules", Map.of("name", "Đêm " + suffix, "enabled", true,
                "scopeType", "SITE", "scopeId", site, "schedule", List.of(Map.of("days", List.of(1, 2, 3, 4, 5, 6, 7),
                        "from", from, "to", to)), "cooldownSeconds", cooldown, "channels", List.of("WEB", "TELEGRAM"),
                "includeSnapshot", true), admin());
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return idOf(r);
    }

    /** Thời điểm hôm nay theo giờ địa điểm (Asia/Ho_Chi_Minh). */
    static Instant local(int hour, int minute) {
        return ZonedDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh")).withHour(hour).withMinute(minute).withSecond(0)
                .withNano(0).toInstant();
    }

    List<UUID> motion(Instant at) {
        return OrganizationContext.callAs(orgId(), () -> evaluator.evaluate(cam, at));
    }

    @Test
    void chuyenDong2340_trongKhung22h06h_guiTelegramKemAnhVaLienKet_1000thiKhongGui() throws Exception {
        rule("22:00", "06:00", 120);
        assertThat(motion(local(10, 0))).isEmpty(); // AC2
        List<UUID> created = motion(local(23, 40));
        assertThat(created).hasSize(1);
        UUID alert = created.get(0);

        // Yêu cầu ảnh sự kiện cho Media Worker qua outbox
        JsonNode req = json.readTree(jdbc.sql("select payload::text from outbox_event where aggregate_id = ?"
                + " and event_type = 'snapshot.requested'").param(alert.toString()).query(String.class).single());
        assertThat(req.get("bucket").asText()).isEqualTo("camhub-snapshots");
        // Thông báo Telegram chờ ảnh (chưa tới lượt gửi)
        OrganizationContext.runAs(orgId(), dispatcher::dispatchOrganization);
        verify(chat, org.mockito.Mockito.never()).telegram(any(), eq("chat-lan-" + suffix), any(), any());

        // Media Worker trả ảnh → gắn ảnh, gửi ngay
        when(objectStorage.open("camhub-snapshots", "k.jpg")).thenReturn(new ByteArrayInputStream(new byte[] {1, 2, 3}));
        MessageProperties p = new MessageProperties();
        p.setContentType("application/json");
        rabbit.send(MessagingTopology.JOB_RESULTS, "snapshot.created", new Message(json.writeValueAsString(Map.of(
                "snapshotId", alert, "organizationId", orgId(), "cameraId", cam, "bucket", "camhub-snapshots",
                "objectKey", "k.jpg", "sizeBytes", 3)).getBytes(StandardCharsets.UTF_8), p));
        await().atMost(Duration.ofSeconds(10)).until(() -> "READY".equals(OrganizationContext.callAs(orgId(), () ->
                jdbc.sql("select snapshot_status from alert where id = ?").param(alert).query(String.class).single())));
        OrganizationContext.runAs(orgId(), dispatcher::dispatchOrganization);
        verify(chat).telegram(eq("bot-token-1"), eq("chat-lan-" + suffix),
                org.mockito.ArgumentMatchers.argThat(t -> t.contains("Cổng sau " + suffix) && t.contains("/alerts?id=" + alert)),
                org.mockito.ArgumentMatchers.argThat(b -> b != null && b.length == 3));
    }

    @Test
    void chongSpam120Giay_3LanTrong1Phut_chi1CanhBao() throws Exception {
        rule("00:00", "00:00", 120); // khung 00:00–00:00 = cả ngày
        Instant t = Instant.now();
        assertThat(motion(t)).hasSize(1);
        assertThat(motion(t.plusSeconds(20))).isEmpty();
        assertThat(motion(t.plusSeconds(50))).isEmpty();
    }

    @Test
    void cameraKhongCoPhatHienChuyenDong_khongTaoQuyTacRieng() throws Exception {
        when(devices.describe(any())).thenReturn(new DeviceInfo("Hik", "DS", "1", "SN", null,
                new Capabilities(false, false, false, true, true, false, false, false),
                List.of(new StreamProfile("p1", "main", "MAIN", "H264", 1920, 1080, 25, 4096, "rtsp://10.9.2.1/1", false))));
        UUID plain = idOf(postJson("/api/v1/cameras", Map.of("zoneId", zone, "name", "Không MD " + suffix, "connection",
                Map.of("protocol", "ONVIF", "host", "10.9.2." + (int) (Math.random() * 200 + 2), "port", 80,
                        "username", "u", "password", "p")), admin()));
        MvcResult r = postJson("/api/v1/motion-rules", Map.of("name", "X", "enabled", true, "scopeType", "CAMERA",
                "scopeId", plain, "cooldownSeconds", 60, "channels", List.of("WEB"), "includeSnapshot", false), admin());
        assertThat(r.getResponse().getContentAsString()).contains("MOTION_NOT_SUPPORTED");
    }

    @Test
    void nhanXuLy_chuyenGiaoChoMinhKemLyDo_dongBatBuocKetQua() throws Exception {
        rule("00:00", "00:00", 0);
        UUID alert = motion(Instant.now()).get(0);
        UUID minh = createUser(admin(), "minh-m-" + suffix + "@camhub.test", "TECHNICIAN", "SITE", site);
        Session l = login("lan-m-" + suffix + "@camhub.test", "User-Pass-2026");
        Session m = login("minh-m-" + suffix + "@camhub.test", "User-Pass-2026");

        // AC1: nhận xử lý → Đang xử lý, phụ trách là Lan
        JsonNode d = json.readTree(mvc.perform(authed(post("/api/v1/alerts/" + alert + "/acknowledge"), l)).andReturn()
                .getResponse().getContentAsString());
        assertThat(d.get("alert").get("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(d.get("assignee").get("id").asText()).isEqualTo(lan.toString());

        // INV-28: không chuyển cho người không thấy cảnh báo
        UUID outsider = createUser(admin(), "viewer-m-" + suffix + "@camhub.test", "VIEWER", "CAMERA",
                insertCamera(site, zone, "Khác " + suffix));
        assertThat(postJson("/api/v1/alerts/" + alert + "/reassign", Map.of("userId", outsider, "reason", "x"), l)
                .getResponse().getContentAsString()).contains("INVALID_ASSIGNEE");

        // AC2: chuyển giao cho Minh kèm lý do → Minh phụ trách, nhận thông báo, lịch sử ghi lại
        d = json.readTree(postJson("/api/v1/alerts/" + alert + "/reassign", Map.of("userId", minh,
                "reason", "Cần kỹ thuật kiểm tra camera"), l).getResponse().getContentAsString());
        assertThat(d.get("assignee").get("id").asText()).isEqualTo(minh.toString());
        assertThat(d.get("handovers").get(0).get("reason").asText()).isEqualTo("Cần kỹ thuật kiểm tra camera");
        assertThat(getJson("/api/v1/me/notifications", m).toString()).contains("Bạn được giao xử lý");

        // AC3: đóng không chọn kết quả → bị từ chối
        MvcResult bad = postJson("/api/v1/alerts/" + alert + "/close", Map.of("note", "ok"), m);
        assertThat(bad.getResponse().getStatus()).isEqualTo(400);
        assertThat(bad.getResponse().getContentAsString()).contains("OUTCOME_REQUIRED");
        d = json.readTree(postJson("/api/v1/alerts/" + alert + "/close", Map.of("outcome", "FALSE_ALARM",
                "note", "Mèo đi qua"), m).getResponse().getContentAsString());
        assertThat(d.get("alert").get("status").asText()).isEqualTo("CLOSED");
        assertThat(d.get("alert").get("outcome").asText()).isEqualTo("FALSE_ALARM");
        assertThat(d.get("history").toString()).contains("IN_PROGRESS").contains("CLOSED");
    }
}
