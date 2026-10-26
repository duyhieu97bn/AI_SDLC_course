package vn.camhub.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;
import vn.camhub.ApiTestSupport;

/** US-44 Đăng nhập bằng LDAP/AD với OpenLDAP thật (cùng image và dữ liệu mẫu như deploy/ldap). */
class DirectoryIT extends ApiTestSupport {

    static final String BIND_PASSWORD = "svc-secret-2026";
    static final Path LDAP_DIR = Path.of("..", "deploy", "ldap");

    @SuppressWarnings("resource")
    static final GenericContainer<?> LDAP = new GenericContainer<>("bitnamilegacy/openldap:2.6.10")
            .withEnv(Map.of("LDAP_ROOT", "dc=camhub,dc=local", "LDAP_ADMIN_USERNAME", "admin",
                    "LDAP_ADMIN_PASSWORD", "admin-secret", "LDAP_SKIP_DEFAULT_TREE", "yes",
                    "LDAP_ALLOW_ANON_BINDING", "no", "LDAP_CONFIGURE_PPOLICY", "yes", "LDAP_PORT_NUMBER", "1389"))
            .withCopyToContainer(MountableFile.forHostPath(LDAP_DIR.resolve("seed.ldif")), "/camhub-ldap/seed.ldif")
            .withCopyToContainer(MountableFile.forHostPath(LDAP_DIR.resolve("init.sh"), 0755),
                    "/docker-entrypoint-initdb.d/10-camhub.sh")
            .withCopyToContainer(org.testcontainers.images.builder.Transferable.of(BIND_PASSWORD),
                    "/run/secrets/ldap_bind_password")
            .withExposedPorts(1389)
            .waitingFor(Wait.forLogMessage(".*slapd starting.*", 1));

    @BeforeAll
    static void start() {
        LDAP.start();
    }

    String url() {
        return "ldap://" + LDAP.getHost() + ":" + LDAP.getMappedPort(1389);
    }

    void configure(String url) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("enabled", true, "url", url,
                "bindDn", "cn=svc-camhub,ou=service,dc=camhub,dc=local", "bindPassword", BIND_PASSWORD,
                "userBase", "ou=people,dc=camhub,dc=local",
                "userFilter", "(&(objectClass=inetOrgPerson)(|(uid={0})(mail={0})))",
                "groupBase", "ou=groups,dc=camhub,dc=local", "memberAttribute", "member",
                "displayNameAttribute", "cn", "mailAttribute", "mail"));
        MvcResult r = mvc.perform(authed(put("/api/v1/directory"), admin()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
    }

    MvcResult loginAttempt(String login, String password) throws Exception {
        return loginRaw(login, password);
    }

    @Test
    void lanDauDangNhapAd_taoTaiKhoanVaiTroTheoNhom_voHieuThiDongBoKhoa_ldapLoiThiAdminVanVao() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID hn = idOf(postJson("/api/v1/sites", Map.of("name", "Chi nhánh HN " + suffix), admin()));
        configure(url());
        assertThat(json.readTree(postJson("/api/v1/directory/test", Map.of(), admin()).getResponse()
                .getContentAsString()).get("ok").asBoolean()).isTrue();
        MvcResult map = mvc.perform(authed(put("/api/v1/directory/mappings"), admin())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(List.of(
                        Map.of("groupDn", "cn=CamHub-SiteMgr-HN,ou=groups,dc=camhub,dc=local",
                                "roleId", roleId("SITE_MANAGER"), "scopeType", "SITE", "scopeId", hn),
                        Map.of("groupDn", "cn=CamHub-Operators,ou=groups,dc=camhub,dc=local",
                                "roleId", roleId("OPERATOR"), "scopeType", "SITE", "scopeId", hn))))).andReturn();
        assertThat(map.getResponse().getStatus()).isEqualTo(200);

        // AC1: Hương đăng nhập lần đầu bằng tài khoản AD → tài khoản DIRECTORY, Site Manager phạm vi Chi nhánh HN
        assertThat(loginAttempt("huong.ad", "sai-mat-khau").getResponse().getStatus()).isEqualTo(401);
        Session huong = login("huong.ad", "AdPass-2026!");
        JsonNode me = getJson("/api/v1/me", huong);
        assertThat(me.get("email").asText()).isEqualTo("huong.ad@camhub.local");
        assertThat(me.get("displayName").asText()).isEqualTo("Nguyễn Thu Hương");
        JsonNode assignments = getJson("/api/v1/users/" + me.get("id").asText() + "/assignments", admin());
        assertThat(assignments.toString()).contains(hn.toString()).contains("DIRECTORY");
        assertThat(getJson("/api/v1/me/permissions", huong).toString()).contains("EXPORT_VIDEO");
        // Lần sau đăng nhập bằng email cũng được
        login("huong.ad@camhub.local", "AdPass-2026!");

        // AC2: tài khoản AD của Tuấn bị khóa → lần đồng bộ kế tiếp vô hiệu hóa tài khoản CamHub
        Session tuan = login("tuan.ad", "AdPass-2026!");
        String tuanId = getJson("/api/v1/me", tuan).get("id").asText();
        var lock = LDAP.execInContainer("bash", "-c", "printf 'dn: uid=tuan.ad,ou=people,dc=camhub,dc=local\\n"
                + "changetype: modify\\nadd: pwdAccountLockedTime\\npwdAccountLockedTime: 000001010000Z\\n' | "
                + "ldapmodify -H ldap://localhost:1389 -D cn=admin,dc=camhub,dc=local -w admin-secret");
        assertThat(lock.getExitCode()).as(lock.getStderr()).isZero();
        JsonNode sync = json.readTree(postJson("/api/v1/directory/sync", Map.of(), admin()).getResponse()
                .getContentAsString());
        assertThat(sync.get("disabled").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(getJson("/api/v1/users/" + tuanId, admin()).get("status").asText()).isEqualTo("DISABLED");
        assertThat(loginAttempt("tuan.ad", "AdPass-2026!").getResponse().getStatus()).isIn(401, 403);

        // AC3: máy chủ thư mục không kết nối được → người dùng thư mục thấy thông báo; Admin cục bộ vẫn đăng nhập
        configure("ldap://" + LDAP.getHost() + ":1");
        MvcResult down = loginAttempt("huong.ad@camhub.local", "AdPass-2026!");
        assertThat(down.getResponse().getStatus()).isEqualTo(503);
        assertThat(down.getResponse().getContentAsString()).contains("DIRECTORY_UNAVAILABLE");
        assertThat(admin().accessToken()).isNotBlank();
        configure(url());
    }
}
