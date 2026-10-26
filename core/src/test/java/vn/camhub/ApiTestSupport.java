package vn.camhub;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.camhub.platform.tenancy.OrganizationContext;

/** Test API đầu-cuối qua MockMvc: đăng nhập thật, JWT thật, RLS thật. */
@AutoConfigureMockMvc
public abstract class ApiTestSupport extends IntegrationTestSupport {

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected ObjectMapper json;
    @Autowired
    protected JdbcClient jdbc;

    public record Session(String accessToken, Cookie refresh, JsonNode body) {
    }

    protected MvcResult loginRaw(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", password)))).andReturn();
    }

    protected Session login(String email, String password) throws Exception {
        MvcResult r = loginRaw(email, password);
        if (r.getResponse().getStatus() != 200) {
            throw new AssertionError("Đăng nhập " + email + " thất bại: " + r.getResponse().getContentAsString());
        }
        JsonNode body = json.readTree(r.getResponse().getContentAsString());
        return new Session(body.get("accessToken").asText(), r.getResponse().getCookie("camhub_refresh"), body);
    }

    protected Session admin() throws Exception {
        return login(ADMIN_EMAIL, ADMIN_PASSWORD);
    }

    protected MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b, Session s) {
        return b.header("Authorization", "Bearer " + s.accessToken());
    }

    protected JsonNode getJson(String url, Session s) throws Exception {
        MvcResult r = mvc.perform(authed(get(url), s)).andReturn();
        if (r.getResponse().getStatus() != 200) {
            throw new AssertionError("GET " + url + " -> " + r.getResponse().getStatus() + " "
                    + r.getResponse().getContentAsString());
        }
        return json.readTree(r.getResponse().getContentAsString());
    }

    protected MvcResult postJson(String url, Object body, Session s) throws Exception {
        return mvc.perform(authed(post(url), s).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andReturn();
    }

    protected UUID idOf(MvcResult r) throws Exception {
        if (r.getResponse().getStatus() / 100 != 2) {
            throw new AssertionError(r.getResponse().getStatus() + " " + r.getResponse().getContentAsString());
        }
        return UUID.fromString(json.readTree(r.getResponse().getContentAsString()).get("id").asText());
    }

    /** Tổ chức do OrganizationBootstrap tạo khi khởi động. */
    protected UUID orgId() {
        return jdbc.sql("select organization_id from camhub_login_lookup(?)").param(ADMIN_EMAIL).query(UUID.class)
                .single();
    }

    protected UUID roleId(String code) {
        return OrganizationContext.callAs(orgId(), () -> jdbc.sql("select id from role where code = ?").param(code)
                .query(UUID.class).single());
    }

    /** Thêm camera trực tiếp vào DB (API thêm camera thuộc S2). */
    protected UUID insertCamera(UUID siteId, UUID zoneId, String name) {
        UUID id = UUID.randomUUID();
        OrganizationContext.runAs(orgId(), () -> jdbc.sql("""
                insert into camera (id, organization_id, site_id, zone_id, name, protocol, stream_url, status)
                values (?, ?, ?, ?, ?, 'RTSP', 'rtsp://sim:554/' || ?, 'ONLINE')
                """).params(id, orgId(), siteId, zoneId, name, id.toString()).update());
        OrganizationContext.runAs(orgId(), () -> jdbc.sql("""
                insert into camera_location_history (organization_id, camera_id, site_id, zone_id) values (?, ?, ?, ?)
                """).params(orgId(), id, siteId, zoneId).update());
        return id;
    }

    protected UUID createUser(Session actor, String email, String role, String scopeType, UUID scopeId)
            throws Exception {
        Map<String, Object> a = new java.util.HashMap<>();
        a.put("roleId", roleId(role));
        a.put("scopeType", scopeType);
        a.put("scopeId", scopeId);
        return idOf(postJson("/api/v1/users", Map.of("email", email, "displayName", email,
                "password", "User-Pass-2026", "assignments", List.of(a)), actor));
    }
}
