package vn.camhub.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import vn.camhub.ApiTestSupport;
import vn.camhub.platform.tenancy.OrganizationContext;

/** US-30 Đăng nhập tài khoản cá nhân – 3 kịch bản Gherkin và các trường hợp an toàn phiên. */
class AuthIT extends ApiTestSupport {

    @Test
    void dangNhapThanhCong_vaoTrangMacDinhTheoVaiTro() throws Exception {
        Session s = admin();
        assertThat(s.accessToken()).isNotBlank();
        assertThat(s.body().get("landingPage").asText()).isEqualTo("/dashboard");
        assertThat(s.refresh().isHttpOnly()).isTrue();
        assertThat(s.refresh().getSecure()).isTrue();
        assertThat(s.refresh().getPath()).isEqualTo("/api/v1/auth");
        assertThat(getJson("/api/v1/me", s).get("permissions").size()).isEqualTo(10);
    }

    @Test
    void operatorVaoTrangXemTrucTiep() throws Exception {
        UUID site = idOf(postJson("/api/v1/sites", Map.of("name", "Site-landing-" + UUID.randomUUID()), admin()));
        createUser(admin(), "op-landing@camhub.test", "OPERATOR", "SITE", site);
        assertThat(login("op-landing@camhub.test", "User-Pass-2026").body().get("landingPage").asText())
                .isEqualTo("/live");
    }

    @Test
    void khoaTaiKhoanSau5LanSai_vaGhiLoginFailed() throws Exception {
        UUID site = idOf(postJson("/api/v1/sites", Map.of("name", "Site-lock-" + UUID.randomUUID()), admin()));
        UUID userId = createUser(admin(), "lock@camhub.test", "VIEWER", "SITE", site);

        for (int i = 1; i <= 4; i++) {
            assertThat(loginRaw("lock@camhub.test", "sai-mat-khau").getResponse().getStatus()).isEqualTo(401);
        }
        MvcResult fifth = loginRaw("lock@camhub.test", "sai-mat-khau");
        assertThat(fifth.getResponse().getStatus()).isEqualTo(423);
        assertThat(fifth.getResponse().getContentAsString()).contains("ACCOUNT_LOCKED");
        // Đã khóa thì mật khẩu đúng cũng không vào được
        assertThat(loginRaw("lock@camhub.test", "User-Pass-2026").getResponse().getStatus()).isEqualTo(423);
        String status = OrganizationContext.callAs(orgId(), () -> jdbc.sql("select status from app_user where id = ?")
                .param(userId).query(String.class).single());
        assertThat(status).isEqualTo("LOCKED");

        // Admin mở khóa (RULE-04)
        mvc.perform(authed(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/users/" + userId), admin())
                .contentType("application/json").content("{\"status\":\"ACTIVE\"}"));
        assertThat(loginRaw("lock@camhub.test", "User-Pass-2026").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void emailKhongTonTaiTraCungLoiVoiSaiMatKhau() throws Exception {
        MvcResult r = loginRaw("khong-ton-tai@camhub.test", "x");
        assertThat(r.getResponse().getStatus()).isEqualTo(401);
        assertThat(r.getResponse().getContentAsString()).contains("INVALID_CREDENTIALS");
    }

    @Test
    void hetPhienSau12GioKhongThaoTac() throws Exception {
        Session s = admin();
        UUID sessionId = sessionIdOf(s);
        OrganizationContext.runAs(orgId(), () -> jdbc.sql(
                "update user_session set last_active_at = now() - interval '12 hours 1 minute' where id = ?")
                .param(sessionId).update());

        MvcResult r = mvc.perform(post("/api/v1/auth/refresh").cookie(s.refresh())).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(401);
        assertThat(r.getResponse().getContentAsString()).contains("SESSION_EXPIRED");
        assertThat(r.getResponse().getCookie("camhub_refresh").getMaxAge()).isZero();
    }

    @Test
    void lamMoiPhienXoayVongToken_dungLaiTokenCuThiThuHoiPhien() throws Exception {
        Session s = admin();
        MvcResult first = mvc.perform(post("/api/v1/auth/refresh").cookie(s.refresh())).andReturn();
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        Cookie rotated = first.getResponse().getCookie("camhub_refresh");
        assertThat(rotated.getValue()).isNotEqualTo(s.refresh().getValue());

        // Kẻ trộm dùng lại token cũ -> cả phiên bị thu hồi, kể cả token mới của người dùng thật
        assertThat(mvc.perform(post("/api/v1/auth/refresh").cookie(s.refresh())).andReturn().getResponse().getStatus())
                .isEqualTo(401);
        assertThat(mvc.perform(post("/api/v1/auth/refresh").cookie(rotated)).andReturn().getResponse().getStatus())
                .isEqualTo(401);
        assertThat(mvc.perform(authed(get("/api/v1/me"), s)).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void dangXuatThuHoiAccessTokenNgay() throws Exception {
        Session s = admin();
        mvc.perform(post("/api/v1/auth/logout").cookie(s.refresh()));
        assertThat(mvc.perform(authed(get("/api/v1/me"), s)).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void khongCoTokenHoacTokenGiaThi401() throws Exception {
        assertThat(mvc.perform(get("/api/v1/me")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get("/api/v1/me").header("Authorization", "Bearer abc.def.ghi")).andReturn()
                .getResponse().getStatus()).isEqualTo(401);
    }

    private UUID sessionIdOf(Session s) throws Exception {
        String payload = s.accessToken().split("\\.")[1];
        var claims = json.readTree(java.util.Base64.getUrlDecoder().decode(payload));
        return UUID.fromString(claims.get("sid").asText());
    }
}
