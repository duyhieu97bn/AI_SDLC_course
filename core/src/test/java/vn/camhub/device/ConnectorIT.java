package vn.camhub.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import vn.camhub.ApiTestSupport;
import vn.camhub.device.api.DeviceControlPort.Capabilities;
import vn.camhub.device.api.DeviceControlPort.DeviceInfo;
import vn.camhub.device.api.DeviceControlPort.StreamProfile;
import vn.camhub.device.application.ConnectorService;
import vn.camhub.device.application.DiscoveryService;
import vn.camhub.platform.tenancy.OrganizationContext;

/** US-06 Kích hoạt bộ kết nối địa điểm, TS-06 định tuyến qua đường hầm. */
class ConnectorIT extends ApiTestSupport {

    @Autowired
    ConnectorService connectors;
    @Autowired
    DiscoveryService discovery;

    UUID site;
    UUID zone;
    String suffix;

    @BeforeEach
    void site() throws Exception {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        site = idOf(postJson("/api/v1/sites", Map.of("name", "Đà Nẵng " + suffix), admin()));
        zone = idOf(postJson("/api/v1/sites/" + site + "/zones", Map.of("name", "Sảnh"), admin()));
    }

    JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    MvcResult agent(String path, Object payload, String basic) throws Exception {
        var req = post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload));
        if (basic != null) {
            req.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(basic.getBytes(StandardCharsets.UTF_8)));
        }
        return mvc.perform(req).andReturn();
    }

    record Active(UUID id, String basic, JsonNode activation) {
    }

    Active activated() throws Exception {
        JsonNode created = body(postJson("/api/v1/connectors", Map.of("siteId", site), admin()));
        String code = created.get("activationCode").asText();
        assertThat(code).matches("[A-Z2-9]{4}-[A-Z2-9]{4}");
        assertThat(created.get("installCommand").asText()).contains(code);
        JsonNode a = body(agent("/api/v1/connector-agent/activate", Map.of("code", code.toLowerCase(),
                "hostname", "dn-box", "version", "0.1.0"), null));
        UUID id = UUID.fromString(a.get("connectorId").asText());
        return new Active(id, id + ":" + a.get("secret").asText(), a);
    }

    Map<String, Object> heartbeat() {
        return Map.of("version", "0.1.0", "uptimeSeconds", 10, "tunnelUp", true, "rxBytes", 0, "txBytes", 0,
                "rxKbps", 120.5, "txKbps", 30.0);
    }

    @Test
    void kichHoatTrong24Gio_heartbeatThiTrucTuyen_authfileChoPhepDungCong() throws Exception {
        Active c = activated();
        assertThat(c.activation().get("tunnel").get("url").asText()).endsWith("/tunnel/");
        assertThat(c.activation().get("tunnel").get("user").asText()).isEqualTo(c.id().toString());

        MvcResult hb = agent("/api/v1/connector-agent/heartbeat", heartbeat(), c.basic());
        assertThat(hb.getResponse().getStatus()).isEqualTo(200);
        int socks = body(hb).get("config").get("socksPort").asInt();
        assertThat(socks).isBetween(21000, 21999);

        JsonNode list = getJson("/api/v1/connectors", admin());
        JsonNode mine = null;
        for (JsonNode n : list) {
            if (n.get("id").asText().equals(c.id().toString())) {
                mine = n;
            }
        }
        assertThat(mine).isNotNull();
        assertThat(mine.get("status").asText()).isEqualTo("ONLINE");
        assertThat(mine.get("lastSeenAt").isNull()).isFalse();

        String auth = Files.readString(TUNNEL_AUTH_DIR.resolve("users.json"));
        assertThat(auth).contains(c.id() + ":" + c.activation().get("tunnel").get("password").asText())
                .contains("^R:0\\\\.0\\\\.0\\\\.0:" + socks + "$");

        // Sai bí mật → 401
        assertThat(agent("/api/v1/connector-agent/heartbeat", heartbeat(), c.id() + ":sai").getResponse().getStatus())
                .isEqualTo(401);
        // Không có heartbeat 90 giây → mất liên lạc
        OrganizationContext.runAs(orgId(), () -> jdbc.sql(
                "update connector set last_seen_at = now() - interval '5 minutes' where id = ?").param(c.id()).update());
        OrganizationContext.runAs(orgId(), connectors::markStaleOrganization);
        assertThat(OrganizationContext.callAs(orgId(), () -> jdbc.sql("select status from connector where id = ?")
                .param(c.id()).query(String.class).single())).isEqualTo("OFFLINE");

        // Thu hồi → Connector bị từ chối
        mvc.perform(authed(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/v1/connectors/" + c.id()), admin()));
        assertThat(agent("/api/v1/connector-agent/heartbeat", heartbeat(), c.basic()).getResponse().getStatus())
                .isEqualTo(401);
        assertThat(Files.readString(TUNNEL_AUTH_DIR.resolve("users.json"))).doesNotContain(c.id().toString());
    }

    @Test
    void maQua25Gio_biTuChoi_maSaiVaMaDaDung() throws Exception {
        JsonNode created = body(postJson("/api/v1/connectors", Map.of("siteId", site), admin()));
        UUID id = UUID.fromString(created.get("connector").get("id").asText());
        OrganizationContext.runAs(orgId(), () -> jdbc.sql(
                "update connector set code_expires_at = now() - interval '1 hour' where id = ?").param(id).update());
        MvcResult r = agent("/api/v1/connector-agent/activate", Map.of("code", created.get("activationCode").asText()),
                null);
        assertThat(r.getResponse().getStatus()).isEqualTo(410);
        assertThat(r.getResponse().getContentAsString()).contains("ACTIVATION_EXPIRED");
        assertThat(agent("/api/v1/connector-agent/activate", Map.of("code", "ZZZZ-ZZZZ"), null).getResponse()
                .getStatus()).isEqualTo(404);

        // Admin tạo mã mới → kích hoạt được; dùng lại mã → bị từ chối
        String code = body(postJson("/api/v1/connectors/" + id + "/activation-code", Map.of(), admin()))
                .get("activationCode").asText();
        assertThat(agent("/api/v1/connector-agent/activate", Map.of("code", code), null).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(agent("/api/v1/connector-agent/activate", Map.of("code", code), null).getResponse().getStatus())
                .isIn(404, 409);
    }

    @Test
    void cameraTaiDiaDiemCoBoKetNoi_diQuaSocksVaCongChuyenTiep() throws Exception {
        Active c = activated();
        int socks = body(agent("/api/v1/connector-agent/heartbeat", heartbeat(), c.basic())).get("config")
                .get("socksPort").asInt();
        when(devices.describe(any())).thenReturn(new DeviceInfo("Hik", "DS", "1", "SN", null,
                new Capabilities(false, false, false, true, true, false, false, false),
                List.of(new StreamProfile("p1", "main", "MAIN", "H264", 1920, 1080, 25, 4096,
                        "rtsp://192.168.1.64:554/Streaming/Channels/101", false))));
        UUID cam = idOf(postJson("/api/v1/cameras", Map.of("zoneId", zone, "connection", Map.of("protocol", "ONVIF",
                "host", "192.168.1.64", "port", 80, "username", "admin", "password", "pw")), admin()));

        // ONVIF qua SOCKS5 của đường hầm
        verify(devices, atLeastOnce()).describe(argThat(a -> ("socks5h://tunnel:" + socks).equals(a.proxy())));
        // MediaMTX kéo RTSP qua cổng chuyển tiếp trên Tunnel Server
        String path = OrganizationContext.callAs(orgId(), () -> jdbc.sql("select media_path from camera where id = ?")
                .param(cam).query(String.class).single());
        verify(mediamtx, atLeastOnce()).upsertPath(eq(path), argThat(p -> p.source().matches(
                "rtsp://admin:pw@tunnel:2\\d{4}/Streaming/Channels/101")));
        // Heartbeat kế tiếp mang cổng chuyển tiếp tới camera trong LAN địa điểm
        JsonNode forwards = body(agent("/api/v1/connector-agent/heartbeat", heartbeat(), c.basic())).get("config")
                .get("forwards");
        assertThat(forwards.toString()).contains("192.168.1.64").contains("554");
        assertThat(Files.readString(TUNNEL_AUTH_DIR.resolve("users.json")))
                .contains(":" + forwards.get(0).get("port").asInt() + "$");
    }

    @Test
    void doTimQuaBoKetNoi_connectorNhanViecVaTraKetQua() throws Exception {
        Active c = activated();
        agent("/api/v1/connector-agent/heartbeat", heartbeat(), c.basic());
        UUID adminId = UUID.fromString(getJson("/api/v1/me", admin()).get("id").asText());
        UUID org = orgId();
        CompletableFuture<List<DiscoveryService.Found>> found = CompletableFuture.supplyAsync(() ->
                OrganizationContext.callAs(org, () -> discovery.discover(adminId, site, List.of())));
        JsonNode[] task = new JsonNode[1];
        Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).until(() -> {
            JsonNode tasks = body(agent("/api/v1/connector-agent/heartbeat", heartbeat(), c.basic())).get("tasks");
            if (tasks.size() > 0) {
                task[0] = tasks.get(0);
                return true;
            }
            return false;
        });
        assertThat(task[0].get("type").asText()).isEqualTo("DISCOVERY");
        MvcResult r = agent("/api/v1/connector-agent/tasks/" + task[0].get("id").asText() + "/result", Map.of(
                "devices", List.of(Map.of("host", "192.168.1.70", "port", 80, "manufacturer", "Dahua",
                        "model", "IPC", "scopes", List.of()))), c.basic());
        assertThat(r.getResponse().getStatus()).isEqualTo(204);
        List<DiscoveryService.Found> devicesFound = found.get(20, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(devicesFound).extracting(DiscoveryService.Found::host).containsExactly("192.168.1.70");
        verify(devices, never()).discover(any(), anyInt());
    }
}
