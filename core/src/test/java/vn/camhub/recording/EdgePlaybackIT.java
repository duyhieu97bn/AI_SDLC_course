package vn.camhub.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import vn.camhub.ApiTestSupport;
import vn.camhub.device.api.DeviceControlPort.Capabilities;
import vn.camhub.device.api.DeviceControlPort.DeviceInfo;
import vn.camhub.device.api.DeviceControlPort.DeviceRecording;
import vn.camhub.device.api.DeviceControlPort.ImagingOptions;
import vn.camhub.device.api.DeviceControlPort.ImagingSettings;
import vn.camhub.device.api.DeviceControlPort.Range;
import vn.camhub.device.api.DeviceControlPort.RecordingRange;
import vn.camhub.device.api.DeviceControlPort.RecordingSearch;
import vn.camhub.device.api.DeviceControlPort.StreamProfile;

/** US-22 Xem lại từ thiết bị (Profile G), US-24 Cấu hình hình ảnh. */
class EdgePlaybackIT extends ApiTestSupport {

    UUID zone;
    String suffix;

    @BeforeEach
    void site() throws Exception {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID site = idOf(postJson("/api/v1/sites", Map.of("name", "Thẻ nhớ " + suffix), admin()));
        zone = idOf(postJson("/api/v1/sites/" + site + "/zones", Map.of("name", "Kho"), admin()));
    }

    UUID camera(boolean edge) throws Exception {
        when(devices.describe(any())).thenReturn(new DeviceInfo("Axis", "P", "1", "SN", null,
                new Capabilities(false, false, false, true, true, edge, false, false),
                List.of(new StreamProfile("p1", "main", "MAIN", "H264", 1920, 1080, 25, 4096, "rtsp://10.7.0.1/1", false))));
        return idOf(postJson("/api/v1/cameras", Map.of("zoneId", zone, "name", (edge ? "G " : "Không G ") + suffix,
                "connection", Map.of("protocol", "ONVIF", "host", "10.7.0." + (int) (Math.random() * 200 + 2),
                        "port", 80, "username", "u", "password", "p")), admin()));
    }

    @Test
    void profileG_timelineTheThietBi_phatQuaPathTam() throws Exception {
        UUID cam = camera(true);
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        when(devices.searchRecordings(any(), any(), any(), any())).thenReturn(new RecordingSearch(true, null, List.of(
                new DeviceRecording("rec_1", "vs0", "t1", List.of(new RecordingRange(now.minusSeconds(7200),
                        now.minusSeconds(3600)), new RecordingRange(now.minusSeconds(1800), now))))));
        when(devices.replayUri(any(), eq("rec_1"), any())).thenReturn("rtsp://10.7.0.1/replay?starttime=x");
        JsonNode tl = getJson("/api/v1/cameras/" + cam + "/edge-timeline?from=" + now.minusSeconds(86400) + "&to=" + now,
                admin());
        assertThat(tl.get("supported").asBoolean()).isTrue();
        assertThat(tl.get("ranges")).hasSize(2);

        // Chọn vào khoảng trống → phát từ đoạn kế tiếp
        JsonNode s = json.readTree(postJson("/api/v1/cameras/" + cam + "/edge-playback-session",
                Map.of("at", now.minusSeconds(3000)), admin()).getResponse().getContentAsString());
        assertThat(s.get("start").asText()).isEqualTo(now.minusSeconds(1800).toString());
        assertThat(s.get("whepUrl").asText()).startsWith("/media/webrtc/edge-");
        verify(mediamtx).upsertPath(argThat(p -> p.startsWith("edge-")),
                argThat(c -> c.source().equals("rtsp://u:p@10.7.0.1/replay?starttime=x") && c.onDemand()));
    }

    @Test
    void khongProfileG_nguonThietBiBiVoHieuKemLyDo() throws Exception {
        UUID cam = camera(false);
        Instant now = Instant.now();
        JsonNode tl = getJson("/api/v1/cameras/" + cam + "/edge-timeline?from=" + now.minusSeconds(3600) + "&to=" + now,
                admin());
        assertThat(tl.get("supported").asBoolean()).isFalse();
        assertThat(tl.get("reason").asText()).contains("Profile G");
    }

    @Test
    void hinhAnh_trongKhoangHoTro_vaKiemTraKhoang() throws Exception {
        UUID cam = camera(false);
        when(devices.imaging(any())).thenReturn(new ImagingSettings("vs0", 50.0, 50.0, "AUTO", false,
                new ImagingOptions(new Range(0, 100), new Range(0, 100), List.of("AUTO", "DAY", "NIGHT"), false)));
        JsonNode img = getJson("/api/v1/cameras/" + cam + "/config/image", admin());
        assertThat(img.get("brightness").asDouble()).isEqualTo(50.0);
        var bad = mvc.perform(authed(put("/api/v1/cameras/" + cam + "/config/image"), admin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"brightness\":150}")).andReturn();
        assertThat(bad.getResponse().getContentAsString()).contains("OUT_OF_RANGE");
        var wdr = mvc.perform(authed(put("/api/v1/cameras/" + cam + "/config/image"), admin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"wdr\":true}")).andReturn();
        assertThat(wdr.getResponse().getContentAsString()).contains("NOT_SUPPORTED");
        var ok = mvc.perform(authed(put("/api/v1/cameras/" + cam + "/config/image"), admin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"brightness\":70,\"irMode\":\"NIGHT\"}")).andReturn();
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        verify(devices).setImaging(any(), eq("vs0"), argThat(c -> c.brightness() == 70.0 && "NIGHT".equals(c.irMode())));
    }
}
