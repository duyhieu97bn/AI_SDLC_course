package vn.camhub.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import vn.camhub.ApiTestSupport;
import vn.camhub.identity.domain.Totp;

/** US-31 Xác thực hai lớp: bắt buộc theo vai trò, mã dự phòng dùng một lần. */
class TwoFactorIT extends ApiTestSupport {

    @Test
    void batBuoc2faTheoVaiTro_phaiThietLapTruocKhiVao_maDuPhongChiDungMotLan() throws Exception {
        Session admin = admin();
        UUID site = idOf(postJson("/api/v1/sites", Map.of("name", "Site 2fa " + UUID.randomUUID()), admin));
        String email = "minh-2fa-" + site + "@camhub.test";
        createUser(admin, email, "TECHNICIAN", "SITE", site);
        setRequired("TECHNICIAN", true, admin);
        try {
            // Bước 1: mật khẩu đúng nhưng chưa có 2FA -> phải thiết lập
            JsonNode step1 = json.readTree(loginRaw(email, "User-Pass-2026").getResponse().getContentAsString());
            assertThat(step1.get("twoFactorRequired").asBoolean()).isTrue();
            assertThat(step1.get("setupRequired").asBoolean()).isTrue();
            assertThat(step1.has("accessToken")).isFalse();
            String challenge = step1.get("challengeId").asText();

            JsonNode setup = json.readTree(postJson("/api/v1/auth/2fa/setup", Map.of("challengeId", challenge), null)
                    .getResponse().getContentAsString());
            String secret = setup.get("secret").asText();
            assertThat(setup.get("otpauthUri").asText()).startsWith("otpauth://totp/CamHub");

            // Mã sai bị từ chối, mã đúng thì vào hệ thống và nhận 10 mã dự phòng
            assertThat(verify(challenge, "000000").getResponse().getStatus()).isEqualTo(401);
            MvcResult ok = verify(challenge, Totp.code(secret, Instant.now().getEpochSecond() / 30));
            assertThat(ok.getResponse().getStatus()).isEqualTo(200);
            JsonNode tokens = json.readTree(ok.getResponse().getContentAsString());
            assertThat(tokens.get("accessToken").asText()).isNotBlank();
            assertThat(tokens.get("recoveryCodes")).hasSize(10);
            String recovery = tokens.get("recoveryCodes").get(0).asText();

            // Lần sau: bước 2 với mã dự phòng (mất điện thoại) – dùng được đúng một lần
            String c2 = json.readTree(loginRaw(email, "User-Pass-2026").getResponse().getContentAsString())
                    .get("challengeId").asText();
            assertThat(verify(c2, recovery).getResponse().getStatus()).isEqualTo(200);
            String c3 = json.readTree(loginRaw(email, "User-Pass-2026").getResponse().getContentAsString())
                    .get("challengeId").asText();
            assertThat(verify(c3, recovery).getResponse().getStatus()).isEqualTo(401);
        } finally {
            setRequired("TECHNICIAN", false, admin());
        }
    }

    @Test
    void tuBat2faTrongHoSo() throws Exception {
        UUID site = idOf(postJson("/api/v1/sites", Map.of("name", "Site 2fa self " + UUID.randomUUID()), admin()));
        String email = "self-2fa-" + site + "@camhub.test";
        createUser(admin(), email, "VIEWER", "SITE", site);
        Session s = login(email, "User-Pass-2026");
        String secret = json.readTree(postJson("/api/v1/me/2fa/setup", Map.of(), s).getResponse().getContentAsString())
                .get("secret").asText();
        MvcResult enabled = postJson("/api/v1/me/2fa/enable",
                Map.of("code", Totp.code(secret, Instant.now().getEpochSecond() / 30)), s);
        assertThat(enabled.getResponse().getStatus()).isEqualTo(200);
        assertThat(getJson("/api/v1/me/2fa", s).get("enabled").asBoolean()).isTrue();
        JsonNode login = json.readTree(loginRaw(email, "User-Pass-2026").getResponse().getContentAsString());
        assertThat(login.get("twoFactorRequired").asBoolean()).isTrue();
        assertThat(login.get("setupRequired").asBoolean()).isFalse();
    }

    private MvcResult verify(String challenge, String code) throws Exception {
        return postJson("/api/v1/auth/2fa/verify", Map.of("challengeId", challenge, "code", code), null);
    }

    @Override
    protected MvcResult postJson(String url, Object body, Session s) throws Exception {
        var b = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(s == null ? b : authed(b, s)).andReturn();
    }

    private void setRequired(String roleCode, boolean required, Session admin) throws Exception {
        MvcResult r = mvc.perform(authed(patch("/api/v1/roles/" + roleId(roleCode) + "/two-factor"), admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"required\":" + required + "}")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
    }
}
