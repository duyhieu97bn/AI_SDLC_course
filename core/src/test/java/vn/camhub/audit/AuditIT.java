package vn.camhub.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.test.web.servlet.MvcResult;
import vn.camhub.ApiTestSupport;
import vn.camhub.platform.tenancy.OrganizationContext;

/** US-33 Nhật ký thao tác: tra cứu và không sửa/xóa được. */
class AuditIT extends ApiTestSupport {

    @Test
    void traCuuThayNguoiThoiDiemIpDoiTuongKetQua() throws Exception {
        Session admin = admin();
        String name = "Site audit " + UUID.randomUUID();
        UUID site = idOf(postJson("/api/v1/sites", Map.of("name", name), admin));

        JsonNode page = getJson("/api/v1/audit-logs?action=CHANGE_CONFIG&targetId=" + site, admin);
        assertThat(page.get("total").asLong()).isEqualTo(1);
        JsonNode e = page.get("items").get(0);
        assertThat(e.get("actorName").asText()).isEqualTo("Quản trị hệ thống");
        assertThat(e.get("ipAddress").asText()).isNotBlank();
        assertThat(e.get("result").asText()).isEqualTo("SUCCESS");
        assertThat(e.get("details").asText()).contains("CREATE_SITE");

        JsonNode logins = getJson("/api/v1/audit-logs?action=LOGIN&size=5", admin);
        assertThat(logins.get("total").asLong()).isPositive();
    }

    @Test
    void dangNhapSaiGhiLoginFailedKetQuaFailure() throws Exception {
        loginRaw(ADMIN_EMAIL, "sai-mat-khau-" + UUID.randomUUID());
        JsonNode page = getJson("/api/v1/audit-logs?action=LOGIN_FAILED&result=FAILURE&size=1", admin());
        assertThat(page.get("items").get(0).get("details").asText()).contains("BAD_PASSWORD");
        // Mở lại tài khoản admin cho các test khác (đếm sai đã tăng 1)
        OrganizationContext.runAs(orgId(), () -> jdbc.sql("update app_user set failed_login_count = 0 where lower(email) = ?")
                .param(ADMIN_EMAIL).update());
    }

    @Test
    void khongSuaKhongXoaDuoc_keCaQuaDbBangTaiKhoanUngDung() {
        OrganizationContext.runAs(orgId(), () -> {
            assertThatThrownBy(() -> jdbc.sql("update audit_log set action = 'X'").update())
                    .isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> jdbc.sql("delete from audit_log").update())
                    .isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> jdbc.sql("truncate audit_log").update())
                    .isInstanceOf(DataAccessException.class);
        });
    }

    @Test
    void xuatCsvCoGhiNhatKy() throws Exception {
        Session admin = admin();
        MvcResult r = mvc.perform(authed(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/v1/audit-logs/export?action=LOGIN"), admin)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getContentType()).startsWith("text/csv");
        assertThat(r.getResponse().getContentAsString()).contains("occurred_at,actor,action");
        assertThat(getJson("/api/v1/audit-logs?action=EXPORT_AUDIT", admin).get("total").asLong()).isPositive();
    }

    @Test
    void chiAdminXemDuocNhatKy() throws Exception {
        UUID site = idOf(postJson("/api/v1/sites", Map.of("name", "Site audit op " + UUID.randomUUID()), admin()));
        createUser(admin(), "audit-op-" + site + "@camhub.test", "OPERATOR", "SITE", site);
        Session op = login("audit-op-" + site + "@camhub.test", "User-Pass-2026");
        MvcResult r = mvc.perform(authed(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/v1/audit-logs"), op)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
    }
}
