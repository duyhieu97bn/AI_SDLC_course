package vn.camhub.device.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import vn.camhub.device.api.DeviceControlPort;

/**
 * Adapter gọi Device Gateway qua REST (TS-05.5). Xác thực: header {@code X-Internal-Token}; nếu cấu hình
 * {@code camhub.device.gateway.ssl-bundle} thì dùng thêm mTLS (staging/production). Circuit breaker
 * "gateway" ngắt nhanh khi Gateway lỗi liên tục, để API không treo theo thiết bị.
 */
@Component
public class GatewayClient implements DeviceControlPort {

    @ConfigurationProperties("camhub.device.gateway")
    public record GatewayProps(String url, String internalToken, String sslBundle, Duration timeout) {
        public GatewayProps {
            if (url == null || url.isBlank()) {
                url = "http://localhost:8000";
            }
            if (timeout == null) {
                timeout = Duration.ofSeconds(40);
            }
        }
    }

    private final RestClient http;

    public GatewayClient(GatewayProps props, RestClient.Builder builder, SslBundles ssl) {
        this.http = build(props, builder, ssl);
    }

    /**
     * Client gọi Device Gateway, dùng chung cho mọi lớp gọi Gateway:
     * <ul>
     *   <li>Ép HTTP/1.1: client JDK mặc định gửi "Upgrade: h2c", uvicorn (h11) từ chối request có body kèm upgrade.</li>
     *   <li>Buffering: luôn có Content-Length thay vì chunked.</li>
     *   <li>mTLS khi cấu hình {@code ssl-bundle}.</li>
     * </ul>
     */
    static RestClient build(GatewayProps props, RestClient.Builder builder, SslBundles ssl) {
        HttpClient.Builder client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5));
        if (ssl != null && props.sslBundle() != null && !props.sslBundle().isBlank()) {
            client.sslContext(ssl.getBundle(props.sslBundle()).createSslContext());
        }
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client.build());
        factory.setReadTimeout(props.timeout());
        return builder.baseUrl(props.url()).requestFactory(new BufferingClientHttpRequestFactory(factory))
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("X-Internal-Token", props.internalToken() == null ? "" : props.internalToken())
                .build();
    }

    @Override
    @CircuitBreaker(name = "gateway")
    public List<DiscoveredDevice> discover(List<String> targets, int timeoutSeconds) {
        return call(() -> List.of(http.post().uri("/v1/discovery")
                .body(Map.of("targets", targets == null ? List.of() : targets, "timeoutSeconds", timeoutSeconds))
                .retrieve().body(DiscoveredDevice[].class)));
    }

    @Override
    @CircuitBreaker(name = "gateway")
    public DeviceInfo describe(DeviceAddress a) {
        return call(() -> http.post().uri("/v1/devices/info").body(address(a)).retrieve().body(DeviceInfo.class));
    }

    @Override
    @CircuitBreaker(name = "gateway")
    public void ptzMove(DeviceAddress a, String profileToken, double pan, double tilt, double zoom, int timeoutMs) {
        post("/v1/devices/ptz/move", Map.of("device", address(a), "profileToken", profileToken, "pan", pan,
                "tilt", tilt, "zoom", zoom, "timeoutMs", timeoutMs));
    }

    @Override
    @CircuitBreaker(name = "gateway")
    public void ptzStop(DeviceAddress a, String profileToken) {
        post("/v1/devices/ptz/stop", Map.of("device", address(a), "profileToken", profileToken));
    }

    @Override
    @CircuitBreaker(name = "gateway")
    public List<Preset> presets(DeviceAddress a, String profileToken) {
        return call(() -> List.of(http.post().uri("/v1/devices/ptz/presets")
                .body(Map.of("device", address(a), "profileToken", profileToken)).retrieve().body(Preset[].class)));
    }

    @Override
    @CircuitBreaker(name = "gateway")
    public void gotoPreset(DeviceAddress a, String profileToken, String presetToken) {
        post("/v1/devices/ptz/presets/goto", Map.of("device", address(a), "profileToken", profileToken,
                "presetToken", presetToken));
    }

    @Override
    @CircuitBreaker(name = "gateway")
    public String setPreset(DeviceAddress a, String profileToken, String name) {
        JsonNode r = call(() -> http.post().uri("/v1/devices/ptz/presets/set")
                .body(Map.of("device", address(a), "profileToken", profileToken, "name", name)).retrieve()
                .body(JsonNode.class));
        return r == null ? null : r.path("token").asText(null);
    }

    @Override
    @CircuitBreaker(name = "gateway")
    public void removePreset(DeviceAddress a, String profileToken, String presetToken) {
        post("/v1/devices/ptz/presets/remove", Map.of("device", address(a), "profileToken", profileToken,
                "presetToken", presetToken));
    }

    @Override
    @CircuitBreaker(name = "gateway")
    public byte[] snapshot(DeviceAddress a, String profileToken) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("device", address(a));
        body.put("profileToken", profileToken);
        return call(() -> http.post().uri("/v1/devices/snapshot").body(body).retrieve().body(byte[].class));
    }

    @Override
    @CircuitBreaker(name = "gateway")
    public List<EncoderConfig> encoders(DeviceAddress a) {
        return call(() -> List.of(http.post().uri("/v1/devices/encoder/get").body(Map.of("device", address(a)))
                .retrieve().body(EncoderConfig[].class)));
    }

    @Override
    @CircuitBreaker(name = "gateway")
    public RecordingSearch searchRecordings(DeviceAddress a, java.time.Instant from, java.time.Instant to,
            String videoSourceToken) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("device", address(a), "from", from.toString(),
                "to", to.toString()));
        if (videoSourceToken != null) {
            body.put("videoSourceToken", videoSourceToken);
        }
        return call(() -> http.post().uri("/v1/devices/recordings/search").body(body).retrieve()
                .body(RecordingSearch.class));
    }

    @Override
    @CircuitBreaker(name = "gateway")
    public String replayUri(DeviceAddress a, String recordingToken, java.time.Instant start) {
        JsonNode r = call(() -> http.post().uri("/v1/devices/recordings/replay-uri").body(Map.of("device", address(a),
                "recordingToken", recordingToken, "start", start.toString())).retrieve().body(JsonNode.class));
        return r == null ? null : r.path("uri").asText(null);
    }

    @Override
    @CircuitBreaker(name = "gateway")
    public NetworkInfo network(DeviceAddress a) {
        return call(() -> http.post().uri("/v1/devices/network/get").body(Map.of("device", address(a))).retrieve()
                .body(NetworkInfo.class));
    }

    @Override
    @CircuitBreaker(name = "gateway")
    public NetworkSetResult setNetwork(DeviceAddress a, NetworkChange c) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("device", address(a), "dhcp", c.dhcp()));
        if (!c.dhcp()) {
            body.put("address", c.address());
            body.put("prefixLength", c.prefixLength());
            if (c.gateway() != null) {
                body.put("gateway", c.gateway());
            }
        }
        if (c.dns() != null && !c.dns().isEmpty()) {
            body.put("dns", c.dns());
        }
        return call(() -> http.post().uri("/v1/devices/network/set").body(body).retrieve()
                .body(NetworkSetResult.class));
    }

    @Override
    @CircuitBreaker(name = "gateway")
    public void setTime(DeviceAddress a, TimeChange c) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("device", address(a), "mode", c.mode()));
        if (c.ntpServers() != null && !c.ntpServers().isEmpty()) {
            body.put("ntpServers", c.ntpServers());
        }
        if (c.utc() != null) {
            body.put("utc", c.utc());
        }
        if (c.timezone() != null) {
            body.put("timezone", c.timezone());
        }
        call(() -> http.post().uri("/v1/devices/time/set").body(body).retrieve().toBodilessEntity());
    }

    @Override
    @CircuitBreaker(name = "gateway")
    public ImagingSettings imaging(DeviceAddress a) {
        return call(() -> http.post().uri("/v1/devices/imaging/get").body(Map.of("device", address(a))).retrieve()
                .body(ImagingSettings.class));
    }

    @Override
    @CircuitBreaker(name = "gateway")
    public void setImaging(DeviceAddress a, String videoSourceToken, ImagingChange c) {
        Map<String, Object> settings = new java.util.HashMap<>();
        if (c.brightness() != null) {
            settings.put("brightness", c.brightness());
        }
        if (c.contrast() != null) {
            settings.put("contrast", c.contrast());
        }
        if (c.irMode() != null) {
            settings.put("irMode", c.irMode());
        }
        if (c.wdr() != null) {
            settings.put("wdr", c.wdr());
        }
        Map<String, Object> body = new java.util.HashMap<>(Map.of("device", address(a), "settings", settings));
        if (videoSourceToken != null) {
            body.put("videoSourceToken", videoSourceToken);
        }
        call(() -> http.post().uri("/v1/devices/imaging/set").body(body).retrieve().toBodilessEntity());
    }

    @Override
    @CircuitBreaker(name = "gateway")
    public RecorderInfo recorderChannels(DeviceAddress a) {
        return call(() -> http.post().uri("/v1/devices/recorder/channels").body(Map.of("device", address(a)))
                .retrieve().body(RecorderInfo.class));
    }

    @Override
    @CircuitBreaker(name = "gateway")
    public void changePassword(DeviceAddress a, String username, String newPassword) {
        call(() -> http.post().uri("/v1/devices/password").body(Map.of("device", address(a), "username", username,
                "newPassword", newPassword)).retrieve().toBodilessEntity());
    }

    @Override
    @CircuitBreaker(name = "gateway")
    public void reboot(DeviceAddress a) {
        call(() -> http.post().uri("/v1/devices/reboot").body(Map.of("device", address(a))).retrieve()
                .toBodilessEntity());
    }

    @Override
    @CircuitBreaker(name = "gateway")
    public ZonesConfig zones(DeviceAddress a) {
        return call(() -> http.post().uri("/v1/devices/zones/get").body(Map.of("device", address(a)))
                .retrieve().body(ZonesConfig.class));
    }

    @Override
    @CircuitBreaker(name = "gateway")
    public void setMotionZones(DeviceAddress a, MotionZonesChange c) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("device", address(a));
        body.put("mode", c.mode());
        if (c.cells() != null) {
            body.put("cells", c.cells());
        }
        if (c.polygons() != null) {
            body.put("polygons", c.polygons());
        }
        if (c.sensitivity() != null) {
            body.put("sensitivity", c.sensitivity());
        }
        call(() -> http.post().uri("/v1/devices/zones/motion/set").body(body).retrieve().toBodilessEntity());
    }

    @Override
    @CircuitBreaker(name = "gateway")
    public void setPrivacyMasks(DeviceAddress a, List<PrivacyMask> masks) {
        List<Map<String, Object>> items = masks.stream()
                .map(m -> Map.<String, Object>of("points", m.points(), "enabled", m.enabled())).toList();
        call(() -> http.post().uri("/v1/devices/zones/masks/set")
                .body(Map.of("device", address(a), "items", items)).retrieve().toBodilessEntity());
    }

    @Override
    @CircuitBreaker(name = "gateway")
    public void setEncoder(DeviceAddress a, EncoderChange c) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("device", address(a));
        body.put("configurationToken", c.configurationToken());
        if (c.codec() != null) {
            body.put("codec", c.codec());
        }
        if (c.width() != null) {
            body.put("width", c.width());
            body.put("height", c.height());
        }
        if (c.fps() != null) {
            body.put("fps", c.fps());
        }
        if (c.bitrateKbps() != null) {
            body.put("bitrateKbps", c.bitrateKbps());
        }
        if (c.govLength() != null) {
            body.put("govLength", c.govLength());
        }
        post("/v1/devices/encoder/set", body);
    }

    @Override
    @CircuitBreaker(name = "gateway")
    public TalkSession openTalk(DeviceAddress a) {
        return call(() -> http.post().uri("/v1/talk/sessions").body(Map.of("device", address(a))).retrieve()
                .body(TalkSession.class));
    }

    @Override
    @CircuitBreaker(name = "gateway")
    public Optional<TalkStatus> talkStatus(String sessionId) {
        return talkCall(() -> http.get().uri("/v1/talk/sessions/{id}", sessionId).retrieve().body(TalkStatus.class));
    }

    @Override
    @CircuitBreaker(name = "gateway")
    public Optional<TalkStatus> closeTalk(String sessionId) {
        return talkCall(() -> http.delete().uri("/v1/talk/sessions/{id}", sessionId).retrieve()
                .body(TalkStatus.class));
    }

    /** 404: Gateway không còn giữ phiên (xử lý trước {@code call} để không tính là lỗi Gateway). */
    private static Optional<TalkStatus> talkCall(java.util.function.Supplier<TalkStatus> work) {
        return call(() -> {
            try {
                return Optional.ofNullable(work.get());
            } catch (HttpClientErrorException.NotFound e) {
                return Optional.<TalkStatus>empty();
            }
        });
    }

    private void post(String path, Map<String, Object> body) {
        call(() -> http.post().uri(path).body(body).retrieve().toBodilessEntity());
    }

    public static Map<String, Object> address(DeviceAddress a) {
        Map<String, Object> m = new java.util.HashMap<>(Map.of("host", a.host(), "port", a.port(),
                "username", a.username() == null ? "" : a.username(), "password", a.password() == null ? "" : a.password()));
        if (a.proxy() != null) {
            m.put("proxy", a.proxy()); // camera sau NAT: Gateway đi qua SOCKS5 của đường hầm địa điểm (TS-06.3)
        }
        return m;
    }

    /** Lỗi thiết bị (HTTP 424 {"code","detail"}) thành DeviceException có mã; lỗi khác là Gateway không dùng được. */
    static <T> T call(java.util.function.Supplier<T> work) {
        try {
            return work.get();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() != 424) {
                throw new GatewayUnavailableException("Device Gateway trả về " + e.getStatusCode().value(), e);
            }
            JsonNode body = e.getResponseBodyAs(JsonNode.class);
            String code = body != null && body.hasNonNull("code") ? body.get("code").asText() : "GATEWAY_ERROR";
            String detail = body != null && body.hasNonNull("detail") ? body.get("detail").asText() : e.getMessage();
            throw new DeviceException(code, detail);
        } catch (ResourceAccessException e) {
            throw new GatewayUnavailableException("Không kết nối được Device Gateway", e);
        }
    }
}
