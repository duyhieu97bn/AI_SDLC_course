package vn.camhub.alerting;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.camhub.ApiTestSupport;
import vn.camhub.alerting.application.AlertingService;
import vn.camhub.device.application.CameraStatusService;
import vn.camhub.notification.application.NotificationDispatcher;
import vn.camhub.platform.tenancy.OrganizationContext;
import vn.camhub.recording.application.StorageMonitorJob;

/** US-34 Cảnh báo mất kết nối, US-35 cảnh báo dung lượng, US-39 tổng quan, thông báo web/email. */
class AlertingIT extends ApiTestSupport {

    static final long GB = 1_073_741_824L;

    @Autowired
    AlertingService alerting;
    @Autowired
    CameraStatusService status;
    @Autowired
    StorageMonitorJob storage;
    @Autowired
    NotificationDispatcher dispatcher;

    String suffix;
    UUID site;
    UUID zone;

    @BeforeEach
    void site() throws Exception {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        site = idOf(postJson("/api/v1/sites", Map.of("name", "Cảnh báo " + suffix), admin()));
        zone = idOf(postJson("/api/v1/sites/" + site + "/zones", Map.of("name", "Khu A"), admin()));
    }

    List<Map<String, Object>> alertsFor(String sourceKey) {
        return OrganizationContext.callAs(orgId(), () -> jdbc.sql(
                "select type, status, severity, title from alert where source_key = ? order by created_at")
                .param(sourceKey).query().listOfRows());
    }

    void evaluate() {
        OrganizationContext.runAs(orgId(), alerting::evaluateOffline);
    }

    @Test
    void sauThoiGianCho_taoCameraOffline_guiNguoiNhan_ketNoiLaiThiPhucHoi() throws Exception {
        UUID cam = insertCamera(site, zone, "Cổng " + suffix);
        UUID other = insertCamera(site, zone, "Kho " + suffix);
        status.apply(other, "ONLINE", Instant.now(), "test");

        // Mất kết nối 2 phút: chưa quá thời gian chờ 5 phút
        status.apply(cam, "OFFLINE", Instant.now().minus(Duration.ofMinutes(2)), "test");
        evaluate();
        assertThat(alertsFor("camera:" + cam)).isEmpty();

        // Mất kết nối 6 phút
        OrganizationContext.runAs(orgId(), () -> jdbc.sql("update camera set status_changed_at = now() - interval '6 minutes'"
                + " where id = ?").param(cam).update());
        evaluate();
        evaluate(); // chạy lại không tạo trùng
        var alerts = alertsFor("camera:" + cam);
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).get("type")).isEqualTo("CAMERA_OFFLINE");
        assertThat(alerts.get(0).get("title").toString()).contains("Cổng " + suffix);

        // Admin (VIEW_LIVE toàn Tổ chức) nhận thông báo web và email chờ gửi
        Session admin = admin();
        assertThat(getJson("/api/v1/me/notifications", admin).toString()).contains("Cổng " + suffix);
        assertThat(OrganizationContext.callAs(orgId(), () -> jdbc.sql(
                "select count(*) from notification where channel = 'EMAIL' and title like ?")
                .param("%Cổng " + suffix + "%").query(Long.class).single())).isPositive();
        assertThat(getJson("/api/v1/alerts?status=OPEN", admin).toString()).contains("Cổng " + suffix);

        // Kết nối lại → Đã phục hồi
        status.apply(cam, "ONLINE", Instant.now(), "test");
        assertThat(alertsFor("camera:" + cam).get(0).get("status")).isEqualTo("RECOVERED");
    }

    @Test
    void ca_diaDiem_matKetNoi_chiTao1SiteOffline() throws Exception {
        List<UUID> cams = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            UUID c = insertCamera(site, zone, "BN" + i + " " + suffix);
            status.apply(c, "OFFLINE", Instant.now().minus(Duration.ofMinutes(6)), "connector down");
            cams.add(c);
        }
        evaluate();
        assertThat(alertsFor("site:" + site)).hasSize(1);
        assertThat(alertsFor("site:" + site).get(0).get("type")).isEqualTo("SITE_OFFLINE");
        for (UUID c : cams) {
            assertThat(alertsFor("camera:" + c)).isEmpty();
        }
        // Một camera kết nối lại → cảnh báo địa điểm phục hồi
        status.apply(cams.get(0), "ONLINE", Instant.now(), "test");
        evaluate();
        assertThat(alertsFor("site:" + site).get(0).get("status")).isEqualTo("RECOVERED");
    }

    @Test
    void vuotNguong85_taoCanhBaoKemDungLuongVaSoNgay_95NangMuc_duoiNguongPhucHoi() throws Exception {
        String vol = "nas-" + suffix;
        storage.record(vol, "/mnt/" + vol, 1000 * GB, 860 * GB);
        var a = alertsFor("storage:" + vol);
        assertThat(a).hasSize(1);
        assertThat(a.get(0).get("severity")).isEqualTo("WARNING");
        assertThat(a.get(0).get("title").toString()).contains("86.0%").contains("còn 140 GB");

        storage.record(vol, "/mnt/" + vol, 1000 * GB, 960 * GB);
        a = alertsFor("storage:" + vol);
        assertThat(a).hasSize(1);
        assertThat(a.get(0).get("severity")).isEqualTo("CRITICAL");

        storage.record(vol, "/mnt/" + vol, 1000 * GB, 500 * GB);
        assertThat(alertsFor("storage:" + vol).get(0).get("status")).isEqualTo("RECOVERED");
    }

    @Test
    void nguongCauHinhDuoc_vaKhongHopLeThiTuChoi() throws Exception {
        Session admin = admin();
        var bad = mvc.perform(authed(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/v1/alert-rules/STORAGE_THRESHOLD"), admin).contentType("application/json")
                .content("{\"enabled\":true,\"params\":{\"warningPercent\":96,\"criticalPercent\":95}}")).andReturn();
        assertThat(bad.getResponse().getStatus()).isEqualTo(400);
        JsonNode rules = getJson("/api/v1/alert-rules", admin);
        assertThat(rules.toString()).contains("offlineDelayMinutes").contains("warningPercent");
    }

    @Test
    void tongQuan_hienThiCameraTrucTuyenVaCanhBaoMo() throws Exception {
        UUID cam = insertCamera(site, zone, "TQ " + suffix);
        status.apply(cam, "OFFLINE", Instant.now().minus(Duration.ofMinutes(10)), "test");
        JsonNode o = getJson("/api/v1/system/overview", admin());
        assertThat(o.get("camerasTotal").asInt()).isGreaterThanOrEqualTo(o.get("camerasOnline").asInt());
        assertThat(o.get("offlineCameras").toString()).contains("TQ " + suffix);
        assertThat(o.has("openAlerts")).isTrue();
        assertThat(o.get("backup").get("available").asBoolean()).isFalse();
        // Operator không xem được tổng quan hệ thống
        createUser(admin(), "op-tq-" + suffix + "@camhub.test", "OPERATOR", "SITE", site);
        Session op = login("op-tq-" + suffix + "@camhub.test", "User-Pass-2026");
        assertThat(mvc.perform(authed(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/v1/system/overview"), op)).andReturn().getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void emailKhongCoSmtp_danhDauThatBai_khongLapVoHan() {
        UUID cam = insertCamera(site, zone, "Mail " + suffix);
        status.apply(cam, "OFFLINE", Instant.now().minus(Duration.ofMinutes(7)), "test");
        evaluate();
        OrganizationContext.runAs(orgId(), dispatcher::dispatchOrganization);
        assertThat(OrganizationContext.callAs(orgId(), () -> jdbc.sql(
                "select status from notification where channel = 'EMAIL' and title like ?")
                .param("%Mail " + suffix + "%").query(String.class).list())).isNotEmpty().allMatch("FAILED"::equals);
    }
}
