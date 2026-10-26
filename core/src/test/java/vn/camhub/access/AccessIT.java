package vn.camhub.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import vn.camhub.ApiTestSupport;

/** US-32 Phân quyền theo vai trò và phạm vi; US-05 Tổ chức, lọc và chuyển camera. */
class AccessIT extends ApiTestSupport {

    UUID bacNinh;
    UUID haNoi;
    UUID zoneBn;
    UUID zoneBn2;
    UUID zoneHn;
    UUID camBn;
    UUID camHn;
    String suffix;

    @BeforeEach
    void twoSites() throws Exception {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        Session admin = admin();
        bacNinh = idOf(postJson("/api/v1/sites", Map.of("name", "Kho Bắc Ninh " + suffix), admin));
        haNoi = idOf(postJson("/api/v1/sites", Map.of("name", "Chi nhánh Hà Nội " + suffix), admin));
        zoneBn = idOf(postJson("/api/v1/sites/" + bacNinh + "/zones", Map.of("name", "Cổng"), admin));
        zoneBn2 = idOf(postJson("/api/v1/sites/" + bacNinh + "/zones", Map.of("name", "Kho hàng"), admin));
        zoneHn = idOf(postJson("/api/v1/sites/" + haNoi + "/zones", Map.of("name", "Sảnh"), admin));
        camBn = insertCamera(bacNinh, zoneBn, "Cổng sau " + suffix);
        camHn = insertCamera(haNoi, zoneHn, "Sảnh HN " + suffix);
    }

    @Test
    void phamViTheoDiaDiem_operatorChiThayCameraCuaDiaDiemMinh() throws Exception {
        createUser(admin(), "lan-" + suffix + "@camhub.test", "OPERATOR", "SITE", bacNinh);
        Session lan = login("lan-" + suffix + "@camhub.test", "User-Pass-2026");

        JsonNode page = getJson("/api/v1/cameras?size=1000", lan);
        List<String> ids = new ArrayList<>();
        page.get("items").forEach(c -> ids.add(c.get("id").asText()));
        assertThat(ids).containsExactly(camBn.toString());
        // Thiếu streamUrl trong phản hồi: URL camera không bao giờ ra trình duyệt
        assertThat(page.get("items").get(0).has("streamUrl")).isFalse();

        assertThat(mvc.perform(authed(get("/api/v1/cameras/" + camHn), lan)).andReturn().getResponse().getStatus())
                .isEqualTo(403);
        JsonNode tree = getJson("/api/v1/sites", lan);
        assertThat(tree).hasSize(1);
        assertThat(tree.get(0).get("id").asText()).isEqualTo(bacNinh.toString());
    }

    @Test
    void thuHoiQuyenCoHieuLucNgay() throws Exception {
        UUID lanId = createUser(admin(), "lan2-" + suffix + "@camhub.test", "OPERATOR", "SITE", bacNinh);
        Session lan = login("lan2-" + suffix + "@camhub.test", "User-Pass-2026");
        assertThat(status(get("/api/v1/cameras/" + camBn), lan)).isEqualTo(200); // nạp cache quyền

        mvc.perform(authed(put("/api/v1/users/" + lanId + "/assignments"), admin())
                .contentType(MediaType.APPLICATION_JSON).content("[]"));

        // Không phải chờ cache 60 giây: version quyền đã tăng nên lần kiểm tra kế tiếp đọc quyền mới
        assertThat(status(get("/api/v1/cameras/" + camBn), lan)).isEqualTo(403);
        assertThat(getJson("/api/v1/cameras", lan).get("total").asLong()).isZero();
    }

    @Test
    void siteManagerChiTaoNguoiDungTrongDiaDiemCuaMinh() throws Exception {
        createUser(admin(), "huong-" + suffix + "@camhub.test", "SITE_MANAGER", "SITE", haNoi);
        Session huong = login("huong-" + suffix + "@camhub.test", "User-Pass-2026");

        // Trong địa điểm của mình: được
        createUser(huong, "nv-hn-" + suffix + "@camhub.test", "OPERATOR", "SITE", haNoi);
        // Địa điểm khác: bị từ chối
        assertThat(createStatus(huong, "nv-bn-" + suffix, "OPERATOR", "SITE", bacNinh)).isEqualTo(403);
        // Toàn Tổ chức: bị từ chối
        assertThat(createStatus(huong, "nv-org-" + suffix, "VIEWER", "ORGANIZATION", null)).isEqualTo(403);
        // Vai trò Admin (có quyền Site Manager không có): bị từ chối
        MvcResult r = createRaw(huong, "nv-admin-" + suffix, "ADMIN", "SITE", haNoi);
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(r.getResponse().getContentAsString()).contains("ROLE_EXCEEDS_OWN_PERMISSIONS");

        // Danh sách người dùng của Hương chỉ gồm người trong địa điểm Hà Nội
        List<String> emails = new ArrayList<>();
        getJson("/api/v1/users", huong).forEach(u -> emails.add(u.get("email").asText()));
        assertThat(emails).contains("nv-hn-" + suffix + "@camhub.test").noneMatch(e -> e.startsWith("lan"));
    }

    @Test
    void chuyenCameraGiuLichSu_vaMotCameraMotKhuVuc() throws Exception {
        Session admin = admin();
        MvcResult r = postJson("/api/v1/cameras/" + camBn + "/move", Map.of("zoneId", zoneBn2), admin);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        JsonNode cam = getJson("/api/v1/cameras/" + camBn, admin);
        assertThat(cam.get("zoneId").asText()).isEqualTo(zoneBn2.toString());

        // Chuyển sang địa điểm khác: site_id đi theo khu vực (INV-02), lịch sử có 3 bản ghi, chỉ 1 đang hiệu lực
        postJson("/api/v1/cameras/" + camBn + "/move", Map.of("zoneId", zoneHn), admin);
        JsonNode history = getJson("/api/v1/cameras/" + camBn + "/location-history", admin);
        assertThat(history).hasSize(3);
        assertThat(history.get(0).get("validTo").isNull()).isTrue();
        assertThat(history.get(0).get("siteId").asText()).isEqualTo(haNoi.toString());
        assertThat(getJson("/api/v1/cameras/" + camBn, admin).get("siteId").asText()).isEqualTo(haNoi.toString());
    }

    @Test
    void technicianKhongChuyenCameraSangDiaDiemKhongDuocGiao() throws Exception {
        createUser(admin(), "minh-" + suffix + "@camhub.test", "TECHNICIAN", "SITE", bacNinh);
        Session minh = login("minh-" + suffix + "@camhub.test", "User-Pass-2026");
        assertThat(postJson("/api/v1/cameras/" + camBn + "/move", Map.of("zoneId", zoneBn2), minh).getResponse()
                .getStatus()).isEqualTo(200);
        assertThat(postJson("/api/v1/cameras/" + camBn + "/move", Map.of("zoneId", zoneHn), minh).getResponse()
                .getStatus()).isEqualTo(403);
    }

    @Test
    void khongXoaKhuVucConCamera() throws Exception {
        MvcResult r = mvc.perform(authed(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/v1/zones/" + zoneBn), admin())).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(r.getResponse().getContentAsString()).contains("ZONE_NOT_EMPTY");
    }

    @Test
    void phamViTheoNhomCamera() throws Exception {
        Session admin = admin();
        UUID group = idOf(postJson("/api/v1/camera-groups", Map.of("name", "Cổng ra vào " + suffix), admin));
        mvc.perform(authed(put("/api/v1/camera-groups/" + group + "/cameras"), admin)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("add", List.of(camHn)))));
        createUser(admin, "viewer-" + suffix + "@camhub.test", "VIEWER", "GROUP", group);
        Session v = login("viewer-" + suffix + "@camhub.test", "User-Pass-2026");
        JsonNode page = getJson("/api/v1/cameras", v);
        assertThat(page.get("total").asLong()).isEqualTo(1);
        assertThat(page.get("items").get(0).get("id").asText()).isEqualTo(camHn.toString());
    }

    @Test
    void loc500CameraDuoiMotGiay() throws Exception {
        Session admin = admin();
        UUID site = idOf(postJson("/api/v1/sites", Map.of("name", "Perf " + suffix), admin));
        UUID zone = idOf(postJson("/api/v1/sites/" + site + "/zones", Map.of("name", "Z"), admin));
        for (int i = 0; i < 500; i++) {
            insertCamera(site, zone, "perf-" + suffix + "-" + i);
        }
        createUser(admin, "perf-" + suffix + "@camhub.test", "OPERATOR", "SITE", site);
        Session op = login("perf-" + suffix + "@camhub.test", "User-Pass-2026");
        getJson("/api/v1/cameras?size=10", op); // làm nóng

        long t0 = System.nanoTime();
        JsonNode r = getJson("/api/v1/cameras?q=perf-" + suffix + "-4&size=1000", op);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(r.get("total").asLong()).isEqualTo(111); // 4, 40..49, 400..499
        assertThat(ms).isLessThan(1000);
    }

    private int status(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b, Session s)
            throws Exception {
        return mvc.perform(authed(b, s)).andReturn().getResponse().getStatus();
    }

    private int createStatus(Session actor, String name, String role, String scopeType, UUID scopeId) throws Exception {
        return createRaw(actor, name, role, scopeType, scopeId).getResponse().getStatus();
    }

    private MvcResult createRaw(Session actor, String name, String role, String scopeType, UUID scopeId)
            throws Exception {
        Map<String, Object> a = new HashMap<>();
        a.put("roleId", roleId(role));
        a.put("scopeType", scopeType);
        a.put("scopeId", scopeId);
        return postJson("/api/v1/users", Map.of("email", name + "@camhub.test", "displayName", name,
                "password", "User-Pass-2026", "assignments", List.of(a)), actor);
    }
}
