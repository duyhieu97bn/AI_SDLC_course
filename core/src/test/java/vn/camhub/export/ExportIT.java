package vn.camhub.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
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
import vn.camhub.export.application.ExportExpiryJob;
import vn.camhub.platform.messaging.MessagingTopology;
import vn.camhub.platform.tenancy.OrganizationContext;

/** US-20 Xuất clip: 4 kịch bản chấp nhận và máy trạng thái ExportJob. */
class ExportIT extends ApiTestSupport {

    @Autowired
    RabbitTemplate rabbit;
    @Autowired
    ExportExpiryJob expiry;

    UUID site;
    UUID cam;
    String suffix;
    Instant base;

    @BeforeEach
    void camera() throws Exception {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        site = idOf(postJson("/api/v1/sites", Map.of("name", "Xuất clip " + suffix), admin()));
        UUID zone = idOf(postJson("/api/v1/sites/" + site + "/zones", Map.of("name", "Cổng"), admin()));
        cam = insertCamera(site, zone, "Cổng " + suffix);
        base = Instant.now().minus(Duration.ofHours(2)).truncatedTo(ChronoUnit.MINUTES);
        for (int i = 0; i < 10; i++) {
            segment(base.plusSeconds(60L * i));
        }
    }

    void segment(Instant start) throws Exception {
        String path = OrganizationContext.callAs(orgId(), () -> jdbc.sql("select media_path from camera where id = ?")
                .param(cam).query(String.class).single());
        String file = "/recordings/" + path + "/"
                + DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneOffset.UTC).format(start)
                + "-000000.mp4";
        mvc.perform(post("/internal/media/segments").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("path", path, "file", file, "duration", "60s"))));
    }

    MvcResult export(Session s, Instant from, Instant to) throws Exception {
        return postJson("/api/v1/exports", Map.of("cameraId", cam, "startAt", from, "endAt", to), s);
    }

    long jobs() {
        return OrganizationContext.callAs(orgId(), () -> jdbc.sql("select count(*) from export_job where camera_id = ?")
                .param(cam).query(Long.class).single());
    }

    void workerReplies(String routingKey, Map<String, Object> body) throws Exception {
        MessageProperties p = new MessageProperties();
        p.setMessageId(UUID.randomUUID().toString());
        p.setContentType("application/json");
        rabbit.send(MessagingTopology.JOB_RESULTS, routingKey,
                new Message(json.writeValueAsString(body).getBytes(StandardCharsets.UTF_8), p));
    }

    String status(UUID id) {
        return OrganizationContext.callAs(orgId(), () -> jdbc.sql("select status from export_job where id = ?")
                .param(id).query(String.class).single());
    }

    @Test
    void xuatThanhCong_phatViecChoWorker_sanSangThiTaiDuoc_ghiNhatKy() throws Exception {
        Session huong = siteManager();
        MvcResult r = export(huong, base.plusSeconds(60), base.plusSeconds(360));
        assertThat(r.getResponse().getStatus()).isEqualTo(202);
        JsonNode job = json.readTree(r.getResponse().getContentAsString());
        UUID id = UUID.fromString(job.get("id").asText());
        assertThat(job.get("status").asText()).isEqualTo("QUEUED");

        // Outbox mang hợp đồng cho Media Worker
        JsonNode payload = json.readTree(jdbc.sql("select payload::text from outbox_event where aggregate_id = ?")
                .param(id.toString()).query(String.class).single());
        assertThat(payload.get("durationSeconds").asLong()).isEqualTo(300);
        assertThat(payload.get("mediaPath").asText()).startsWith("cam-");
        assertThat(payload.get("objectKey").asText()).isEqualTo(orgId() + "/" + id + ".mp4");

        // Nhật ký EXPORT_VIDEO
        assertThat(OrganizationContext.callAs(orgId(), () -> jdbc.sql(
                "select count(*) from audit_log where action = 'EXPORT_VIDEO' and target_id = ?")
                .param(cam.toString()).query(Long.class).single())).isEqualTo(1);

        workerReplies("export.progress", Map.of("exportId", id, "organizationId", orgId(), "state", "PROCESSING"));
        await().atMost(Duration.ofSeconds(10)).until(() -> status(id).equals("PROCESSING"));
        workerReplies("export.completed", Map.of("exportId", id, "organizationId", orgId(), "sizeBytes", 4,
                "sha256", "ab", "bucket", "camhub-exports", "objectKey", orgId() + "/" + id + ".mp4"));
        await().atMost(Duration.ofSeconds(10)).until(() -> status(id).equals("READY"));

        // Thông báo "clip sẵn sàng" trên chuông của người xuất
        JsonNode notes = getJson("/api/v1/me/notifications", huong);
        assertThat(notes.get(0).get("title").asText()).contains("Clip đã sẵn sàng");

        JsonNode link = json.readTree(postJson("/api/v1/exports/" + id + "/download-link", Map.of(), huong)
                .getResponse().getContentAsString());
        when(objectStorage.open("camhub-exports", orgId() + "/" + id + ".mp4"))
                .thenReturn(new ByteArrayInputStream(new byte[] {0, 0, 0, 24}));
        MvcResult file = mvc.perform(get(link.get("url").asText())).andReturn();
        if (file.getRequest().isAsyncStarted()) {
            file = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(file))
                    .andReturn();
        }
        assertThat(file.getResponse().getStatus()).isEqualTo(200);
        assertThat(file.getResponse().getContentType()).isEqualTo("video/mp4");
        assertThat(file.getResponse().getContentAsByteArray()).hasSize(4);

        // Liên kết bị sửa chữ ký thì từ chối
        assertThat(mvc.perform(get(link.get("url").asText().replaceAll("sig=[^&]+", "sig=AAAA"))).andReturn()
                .getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void qua60Phut_yeuCauRutNgan_khongTaoCongViec() throws Exception {
        MvcResult r = export(admin(), base, base.plus(Duration.ofMinutes(75)));
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(r.getResponse().getContentAsString()).contains("EXPORT_RANGE_TOO_LONG");
        assertThat(jobs()).isZero();
    }

    @Test
    void operatorKhongCoExportVideo_biTuChoi() throws Exception {
        createUser(admin(), "lan-ex-" + suffix + "@camhub.test", "OPERATOR", "SITE", site);
        Session lan = login("lan-ex-" + suffix + "@camhub.test", "User-Pass-2026");
        assertThat(export(lan, base, base.plusSeconds(120)).getResponse().getStatus()).isEqualTo(403);
        assertThat(getJson("/api/v1/me/permissions", lan).toString()).doesNotContain("EXPORT_VIDEO");
        assertThat(jobs()).isZero();
    }

    @Test
    void lienKetHetHan_sau24Gio_trangThaiHetHan_xoaTep() throws Exception {
        Session admin = admin();
        UUID id = idOf(export(admin, base, base.plusSeconds(120)));
        workerReplies("export.completed", Map.of("exportId", id, "organizationId", orgId(), "sizeBytes", 10,
                "sha256", "cd"));
        await().atMost(Duration.ofSeconds(10)).until(() -> status(id).equals("READY"));
        // Giả lập clip READY từ 25 giờ trước
        OrganizationContext.runAs(orgId(), () -> jdbc.sql("update export_job set completed_at = now() - interval '25 hours',"
                + " expires_at = now() - interval '1 hour' where id = ?").param(id).update());

        MvcResult r = postJson("/api/v1/exports/" + id + "/download-link", Map.of(), admin);
        assertThat(r.getResponse().getStatus()).isEqualTo(410);
        assertThat(r.getResponse().getContentAsString()).contains("EXPORT_EXPIRED");
        assertThat(getJson("/api/v1/exports", admin).toString()).contains("EXPIRED");

        OrganizationContext.runAs(orgId(), expiry::runCurrentOrganization);
        assertThat(status(id)).isEqualTo("EXPIRED");
        verify(objectStorage).delete("camhub-exports", orgId() + "/" + id + ".mp4");
    }

    @Test
    void khongCoVideoTrongKhoang_baoLoi() throws Exception {
        MvcResult r = export(admin(), base.minus(Duration.ofHours(5)), base.minus(Duration.ofHours(5)).plusSeconds(60));
        assertThat(r.getResponse().getContentAsString()).contains("NO_RECORDING");
    }

    Session siteManager() throws Exception {
        createUser(admin(), "huong-ex-" + suffix + "@camhub.test", "SITE_MANAGER", "SITE", site);
        return login("huong-ex-" + suffix + "@camhub.test", "User-Pass-2026");
    }
}
