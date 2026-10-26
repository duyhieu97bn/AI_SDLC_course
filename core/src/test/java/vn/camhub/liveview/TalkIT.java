package vn.camhub.liveview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.camhub.ApiTestSupport;
import vn.camhub.device.api.DeviceControlPort.DeviceException;
import vn.camhub.device.api.DeviceControlPort.TalkSession;
import vn.camhub.device.api.DeviceControlPort.TalkStatus;
import vn.camhub.liveview.application.TalkAuditService;
import vn.camhub.platform.tenancy.OrganizationContext;

/** US-15 Nhấn giữ để nói: quyền TALK_AUDIO, camera có loa, nhật ký TALK_AUDIO kèm thời lượng. */
class TalkIT extends ApiTestSupport {

    @Autowired
    TalkAuditService talkAudit;

    @Test
    void operatorNoiQuaLoa_nhaNutGhiNhatKyKemThoiLuong() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Session admin = admin();
        UUID site = idOf(postJson("/api/v1/sites", Map.of("name", "Kho loa " + suffix), admin));
        UUID zone = idOf(postJson("/api/v1/sites/" + site + "/zones", Map.of("name", "Cổng"), admin));
        UUID speaker = onvifCamera(site, zone, "Loa cổng " + suffix, true);
        UUID mute = onvifCamera(site, zone, "Không loa " + suffix, false);
        createUser(admin, "op-" + suffix + "@camhub.test", "OPERATOR", "SITE", site);
        createUser(admin, "view-" + suffix + "@camhub.test", "VIEWER", "SITE", site);
        Session operator = login("op-" + suffix + "@camhub.test", "User-Pass-2026");
        Session viewer = login("view-" + suffix + "@camhub.test", "User-Pass-2026");

        String sid = "talk-" + suffix;
        when(devices.openTalk(any())).thenReturn(new TalkSession(sid, "PCMU", 30, 8000));
        Instant started = Instant.parse("2026-10-26T08:00:00Z");
        when(devices.closeTalk(sid)).thenReturn(Optional.of(new TalkStatus(sid, "ENDED", started,
                started.plusMillis(4200), 4.2, 4.0, null)));

        JsonNode start = json.readTree(postJson("/api/v1/cameras/" + speaker + "/talk", Map.of(), operator)
                .getResponse().getContentAsString());
        assertThat(start.get("wsUrl").asText()).isEqualTo("/talk/ws/" + sid);
        assertThat(start.get("sampleRate").asInt()).isEqualTo(8000);

        // Người khác không kết thúc được phiên của Operator
        assertThat(postJson("/api/v1/cameras/" + speaker + "/talk/" + sid + "/end", Map.of(), admin)
                .getResponse().getStatus()).isEqualTo(404);

        JsonNode end = json.readTree(postJson("/api/v1/cameras/" + speaker + "/talk/" + sid + "/end", Map.of(),
                operator).getResponse().getContentAsString());
        assertThat(end.get("durationSeconds").asDouble()).isEqualTo(4.2);
        JsonNode details = json.readTree(OrganizationContext.callAs(orgId(), () -> jdbc.sql(
                "select details::text from audit_log where action = 'TALK_AUDIO' and target_id = ?")
                .param(speaker.toString()).query(String.class).single()));
        assertThat(details.get("durationSeconds").asDouble()).isEqualTo(4.2);
        assertThat(details.get("audioSeconds").asDouble()).isEqualTo(4.0);
        // Ghi một lần: kết thúc lần nữa là phiên không còn
        assertThat(postJson("/api/v1/cameras/" + speaker + "/talk/" + sid + "/end", Map.of(), operator)
                .getResponse().getStatus()).isEqualTo(404);

        // Viewer không có TALK_AUDIO; camera không loa; camera đang có người nói
        assertThat(postJson("/api/v1/cameras/" + speaker + "/talk", Map.of(), viewer).getResponse().getStatus())
                .isEqualTo(403);
        assertThat(postJson("/api/v1/cameras/" + mute + "/talk", Map.of(), operator).getResponse()
                .getContentAsString()).contains("TALK_NOT_SUPPORTED");
        when(devices.openTalk(any())).thenThrow(new DeviceException("BUSY", "Đang có người đàm thoại"));
        assertThat(postJson("/api/v1/cameras/" + speaker + "/talk", Map.of(), operator).getResponse()
                .getContentAsString()).contains("TALK_BUSY");
    }

    @Test
    void dongTabKhongNhaNut_quetDinhKyVanGhiNhatKy() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Session admin = admin();
        UUID site = idOf(postJson("/api/v1/sites", Map.of("name", "Kho quét " + suffix), admin));
        UUID zone = idOf(postJson("/api/v1/sites/" + site + "/zones", Map.of("name", "Sân"), admin));
        UUID cam = onvifCamera(site, zone, "Loa sân " + suffix, true);
        String sid = "sweep-" + suffix;
        when(devices.openTalk(any())).thenReturn(new TalkSession(sid, "PCMA", 30, 8000));
        mvc.perform(authed(post("/api/v1/cameras/" + cam + "/talk"), admin));

        when(devices.talkStatus(sid)).thenReturn(Optional.of(new TalkStatus(sid, "ACTIVE", Instant.now(), null,
                2, 2, null)));
        talkAudit.sweep();
        assertThat(talkCount(cam)).isZero();

        when(devices.talkStatus(sid)).thenReturn(Optional.of(new TalkStatus(sid, "FAILED", Instant.now(),
                Instant.now(), 7.5, 7.5, "Camera đóng kênh âm thanh")));
        talkAudit.sweep();
        talkAudit.sweep();
        assertThat(talkCount(cam)).isEqualTo(1);
    }

    private long talkCount(UUID cam) {
        return OrganizationContext.callAs(orgId(), () -> jdbc.sql(
                "select count(*) from audit_log where action = 'TALK_AUDIO' and target_id = ?")
                .param(cam.toString()).query(Long.class).single());
    }

    private UUID onvifCamera(UUID site, UUID zone, String name, boolean speaker) {
        UUID id = insertCamera(site, zone, name);
        OrganizationContext.runAs(orgId(), () -> jdbc.sql("""
                update camera set protocol = 'ONVIF', ip_address = '10.88.0.' || (random() * 200 + 1)::int,
                       port = 80, username = 'admin', capabilities = cast(? as jsonb) where id = ?
                """).params("{\"audioOut\": " + speaker + "}", id).update());
        return id;
    }
}
