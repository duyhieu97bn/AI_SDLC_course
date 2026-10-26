package vn.camhub.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import vn.camhub.device.api.DeviceControlPort.Capabilities;
import vn.camhub.device.api.DeviceControlPort.DeviceException;
import vn.camhub.device.api.DeviceControlPort.DeviceInfo;
import vn.camhub.device.api.DeviceControlPort.EncoderConfig;
import vn.camhub.device.api.DeviceControlPort.EncoderOptions;
import vn.camhub.device.api.DeviceControlPort.IntRange;
import vn.camhub.device.api.DeviceControlPort.Resolution;
import vn.camhub.device.api.DeviceControlPort.StreamProfile;
import vn.camhub.platform.messaging.MessagingTopology;
import vn.camhub.platform.tenancy.OrganizationContext;
import vn.camhub.recording.application.RecordingService;

/** US-16 Chế độ ghi, US-17 Xem lại, US-21 Lưu trữ và giữ lại, US-23 Cấu hình luồng. */
class RecordingIT extends ApiTestSupport {

    @Autowired
    RecordingService recording;
    @Autowired
    RabbitTemplate rabbit;

    UUID site;
    UUID zone;
    String suffix;

    @BeforeEach
    void site() throws Exception {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        site = idOf(postJson("/api/v1/sites", Map.of("name", "Ghi hình " + suffix), admin()));
        zone = idOf(postJson("/api/v1/sites/" + site + "/zones", Map.of("name", "Quầy"), admin()));
    }

    String mediaPath(UUID cam) {
        return OrganizationContext.callAs(orgId(), () -> jdbc.sql("select media_path from camera where id = ?")
                .param(cam).query(String.class).single());
    }

    void segment(UUID cam, Instant start, int seconds) throws Exception {
        String file = "/recordings/" + mediaPath(cam) + "/"
                + java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(java.time.ZoneOffset.UTC)
                        .format(start) + "-000000.mp4";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/internal/media/segments")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("path", mediaPath(cam),
                        "file", file, "duration", seconds + "s"))));
    }

    int segmentCount(UUID cam) {
        return OrganizationContext.callAs(orgId(), () -> jdbc.sql("select count(*) from recording_segment where camera_id = ?")
                .param(cam).query(Integer.class).single());
    }

    @Test
    void cheDoLienTuc_batGhiTrenMediaMtx_apChoNhieuCamera() throws Exception {
        UUID c1 = insertCamera(site, zone, "A " + suffix);
        UUID c2 = insertCamera(site, zone, "B " + suffix);
        JsonNode r = json.readTree(mvc.perform(authed(put("/api/v1/recording-policies"), admin())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("cameraIds", List.of(c1, c2),
                        "policy", Map.of("mode", "CONTINUOUS", "preEventSeconds", 10, "postEventSeconds", 30)))))
                .andReturn().getResponse().getContentAsString());
        assertThat(r).hasSize(2);
        assertThat(r.get(0).get("ok").asBoolean()).isTrue();
        verify(mediamtx, atLeastOnce()).upsertPath(eq(mediaPath(c1)), argThat(c -> c.record() && !c.onDemand()));
        // Ô xem trực tiếp hiển thị REC
        JsonNode live = json.readTree(postJson("/api/v1/cameras/" + c1 + "/live-session", Map.of(), admin())
                .getResponse().getContentAsString());
        assertThat(live.get("recording").asBoolean()).isTrue();
    }

    @Test
    void ghiTheoSuKien_giuDoanQuanhChuyenDong_xoaDoanKhongCoSuKien() throws Exception {
        UUID cam = insertCamera(site, zone, "Kho " + suffix);
        mvc.perform(authed(put("/api/v1/cameras/" + cam + "/recording-policy"), admin()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("mode", "EVENT", "preEventSeconds", 10, "postEventSeconds", 30))));
        Instant base = Instant.now().minus(Duration.ofMinutes(20)).truncatedTo(ChronoUnit.MINUTES);
        for (int i = 0; i < 4; i++) {
            segment(cam, base.plusSeconds(60L * i), 60);
        }
        // Chuyển động lúc base + 125 giây: cửa sổ [115, 155] giao đoạn 60–120 và 120–180; đoạn 0–60, 180–240 bị xóa
        MessageProperties p = new MessageProperties();
        p.setMessageId(UUID.randomUUID().toString());
        p.setContentType("application/json");
        rabbit.send(MessagingTopology.DEVICE_EVENTS, "motion.detected", new Message(json.writeValueAsString(Map.of(
                "cameraId", cam.toString(), "state", true, "occurredAt", base.plusSeconds(125).toString()))
                .getBytes(StandardCharsets.UTF_8), p));
        await().atMost(Duration.ofSeconds(10)).until(() -> OrganizationContext.callAs(orgId(), () ->
                jdbc.sql("select count(*) from recording_event where camera_id = ?").param(cam).query(Integer.class).single()) == 1);

        recording.cleanEventModeRecordings();
        JsonNode tl = getJson("/api/v1/cameras/" + cam + "/timeline?from=" + base.minusSeconds(60) + "&to="
                + base.plusSeconds(600), admin());
        assertThat(tl.get("recordings")).hasSize(1);
        assertThat(tl.get("recordings").get(0).get("start").asText()).isEqualTo(base.plusSeconds(60).toString());
        assertThat(tl.get("recordings").get(0).get("end").asText()).isEqualTo(base.plusSeconds(180).toString());
        assertThat(tl.get("events")).hasSize(1);
    }

    @Test
    void xemLai_nhayToiDoanGanNhat_ghiNhatKy_vaQuyen() throws Exception {
        UUID cam = insertCamera(site, zone, "Quầy 1 " + suffix);
        Instant t = Instant.now().minus(Duration.ofHours(2)).truncatedTo(ChronoUnit.MINUTES);
        segment(cam, t, 60);
        segment(cam, t.plusSeconds(60), 60);
        segment(cam, t.plusSeconds(600), 60); // khoảng trống 2–10 phút

        JsonNode tl = getJson("/api/v1/cameras/" + cam + "/timeline?from=" + t.minusSeconds(3600) + "&to="
                + t.plusSeconds(3600), admin());
        assertThat(tl.get("recordings")).hasSize(2); // hai đoạn đầu gộp lại
        JsonNode s = json.readTree(postJson("/api/v1/cameras/" + cam + "/playback-session",
                Map.of("at", t.plusSeconds(300).toString()), admin()).getResponse().getContentAsString());
        assertThat(s.get("jumped").asBoolean()).isTrue();
        assertThat(s.get("start").asText()).isEqualTo(t.plusSeconds(600).toString());
        assertThat(s.get("url").asText()).startsWith("/media/playback/get?path=" + mediaPath(cam)).contains("format=fmp4");
        assertThat(getJson("/api/v1/audit-logs?action=VIEW_PLAYBACK&targetId=" + cam, admin()).get("total").asLong())
                .isEqualTo(1);
        assertThat(getJson("/api/v1/cameras/" + cam + "/recording-days?month=" + t.toString().substring(0, 7), admin()))
                .isNotEmpty();

        createUser(admin(), "tech-pb-" + suffix + "@camhub.test", "TECHNICIAN", "SITE", site);
        Session tech = login("tech-pb-" + suffix + "@camhub.test", "User-Pass-2026");
        assertThat(postJson("/api/v1/cameras/" + cam + "/playback-session", Map.of("at", t.toString()), tech)
                .getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void luuTru_ghiDeTheoCamera_vaGiuLaiPhucVuDieuTra() throws Exception {
        UUID normal = insertCamera(site, zone, "Thường " + suffix);
        UUID quay1 = insertCamera(site, zone, "Quầy 1 " + suffix);
        UUID held = insertCamera(site, zone, "Giữ " + suffix);
        Instant old = Instant.now().minus(Duration.ofDays(40)).truncatedTo(ChronoUnit.SECONDS);
        for (UUID c : List.of(normal, quay1, held)) {
            insertRawSegment(c, old);
        }
        mvc.perform(authed(put("/api/v1/retention-policy"), admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"defaultRetentionDays\":30}"));
        mvc.perform(authed(put("/api/v1/cameras/" + quay1 + "/retention"), admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"retentionDays\":90}"));
        UUID hold = idOf(postJson("/api/v1/evidence-holds", Map.of("cameraId", held, "startAt", old.minusSeconds(60).toString(),
                "endAt", old.plusSeconds(120).toString(), "reason", "KH-2026-1015"), admin()));

        postJson("/api/v1/retention/purge", Map.of(), admin());
        assertThat(segmentCount(normal)).isZero();
        assertThat(segmentCount(quay1)).isEqualTo(1); // ghi đè 90 ngày
        assertThat(segmentCount(held)).isEqualTo(1);  // đang giữ lại

        postJson("/api/v1/evidence-holds/" + hold + "/release", Map.of(), admin());
        postJson("/api/v1/retention/purge", Map.of(), admin());
        assertThat(segmentCount(held)).isZero();
        assertThat(getJson("/api/v1/audit-logs?action=HOLD_EVIDENCE&targetId=" + held, admin()).get("total").asLong())
                .isEqualTo(1);
    }

    @Test
    void operatorKhongCoQuyenGiuLai() throws Exception {
        UUID cam = insertCamera(site, zone, "Op " + suffix);
        createUser(admin(), "op-hold-" + suffix + "@camhub.test", "OPERATOR", "SITE", site);
        Session op = login("op-hold-" + suffix + "@camhub.test", "User-Pass-2026");
        MvcResult r = postJson("/api/v1/evidence-holds", Map.of("cameraId", cam, "startAt", Instant.now().minusSeconds(600)
                .toString(), "endAt", Instant.now().toString(), "reason", "x"), op);
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void cauHinhLuong_kiemTraKhaNang_vaKetQuaTungThamSo() throws Exception {
        when(devices.describe(any())).thenReturn(new DeviceInfo("Hik", "DS", "1", "SN", null,
                new Capabilities(false, false, false, true, true, false, true, false),
                List.of(new StreamProfile("p1", "main", "MAIN", "H264", 1920, 1080, 25, 4096, "rtsp://x/1", false))));
        UUID cam = idOf(postJson("/api/v1/cameras", Map.of("zoneId", zone, "connection", Map.of("protocol", "ONVIF",
                "host", "10.9.9.9", "port", 80, "username", "u", "password", "p")), admin()));
        EncoderOptions opts = new EncoderOptions(List.of("H264", "H265"), List.of(new Resolution(1920, 1080),
                new Resolution(1280, 720)), new IntRange(1, 25), new IntRange(256, 8192), new IntRange(10, 100));
        when(devices.encoders(any())).thenReturn(List.of(new EncoderConfig("enc1", "Main", "H264", 1920, 1080, 25, 4096,
                50, false, opts)));

        // Ngoài khả năng camera: báo lỗi theo tham số, không gửi xuống thiết bị (US-29)
        JsonNode bad = json.readTree(mvc.perform(authed(put("/api/v1/cameras/" + cam + "/config/stream"), admin())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("changes",
                        List.of(Map.of("configurationToken", "enc1", "fps", 60))))))
                .andReturn().getResponse().getContentAsString());
        assertThat(bad.get("result").asText()).isEqualTo("FAILED");
        assertThat(bad.get("parameters").get(0).get("parameter").asText()).isEqualTo("fps");
        verify(devices, never()).setEncoder(any(), any());

        // Camera từ chối bitrate: kết quả một phần
        doThrow(new DeviceException("DEVICE_ERROR", "Bitrate không hợp lệ")).when(devices)
                .setEncoder(any(), argThat(c -> c != null && c.bitrateKbps() != null));
        JsonNode partial = json.readTree(mvc.perform(authed(put("/api/v1/cameras/" + cam + "/config/stream"), admin())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("changes",
                        List.of(Map.of("configurationToken", "enc1", "fps", 15, "bitrateKbps", 2048))))))
                .andReturn().getResponse().getContentAsString());
        assertThat(partial.get("result").asText()).isEqualTo("PARTIAL");
        assertThat(partial.get("parameters").toString()).contains("\"parameter\":\"fps\",\"ok\":true")
                .contains("\"parameter\":\"bitrateKbps\",\"ok\":false");
    }

    private void insertRawSegment(UUID cam, Instant start) {
        OrganizationContext.runAs(orgId(), () -> jdbc.sql("""
                insert into recording_segment (organization_id, camera_id, start_at, end_at, storage_location, size_bytes)
                values (?, ?, ?, ?, '/recordings/khong-ton-tai.mp4', 1000)
                """).params(orgId(), cam, Timestamp.from(start), Timestamp.from(start.plusSeconds(60))).update());
    }
}
