package vn.camhub.device.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * API điều khiển MediaMTX v3 (SADD 3.7.3: Core → MediaMTX, tạo/xóa path cho mỗi camera).
 * Cấu hình path tạo qua API không được lưu khi MediaMTX khởi động lại, nên {@code MediaMtxSync} đồng bộ lại định kỳ.
 */
@Component
public class MediaMtxClient {

    @ConfigurationProperties("camhub.media.mediamtx")
    public record MediaMtxProps(String apiUrl) {
        public MediaMtxProps {
            if (apiUrl == null || apiUrl.isBlank()) {
                apiUrl = "http://localhost:9997";
            }
        }
    }

    /** Cấu hình một path: nguồn RTSP (có tài khoản), kéo khi có người xem hay luôn kéo, có ghi hình không. */
    public record PathConfig(String source, boolean onDemand, boolean record) {
    }

    public record PathState(String name, boolean ready, List<String> tracks, int readers) {
    }

    private static final Logger log = LoggerFactory.getLogger(MediaMtxClient.class);
    private final RestClient http;

    public MediaMtxClient(MediaMtxProps props, RestClient.Builder builder) {
        var settings = ClientHttpRequestFactorySettings.defaults().withConnectTimeout(Duration.ofSeconds(3))
                .withReadTimeout(Duration.ofSeconds(10));
        this.http = builder.baseUrl(props.apiUrl())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings)).build();
    }

    /** Tạo path, hoặc cập nhật nếu đã có. */
    public void upsertPath(String name, PathConfig cfg) {
        Map<String, Object> body = new HashMap<>();
        body.put("source", cfg.source());
        body.put("sourceOnDemand", cfg.onDemand());
        body.put("rtspTransport", "tcp");
        body.put("record", cfg.record());
        try {
            http.post().uri("/v3/config/paths/add/{name}", name).body(body).retrieve().toBodilessEntity();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() != HttpStatus.BAD_REQUEST) {
                throw e;
            }
            // "path already exists": cập nhật thay vì tạo
            http.patch().uri("/v3/config/paths/patch/{name}", name).body(body).retrieve().toBodilessEntity();
        }
    }

    public void deletePath(String name) {
        try {
            http.delete().uri("/v3/config/paths/delete/{name}", name).retrieve().toBodilessEntity();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() != HttpStatus.NOT_FOUND && e.getStatusCode() != HttpStatus.BAD_REQUEST) {
                throw e;
            }
        }
    }

    /** Tên các path đã cấu hình qua API (không gồm path regex trong mediamtx.yml). */
    public List<String> configuredPaths() {
        JsonNode r = http.get().uri("/v3/config/paths/list?itemsPerPage=10000").retrieve().body(JsonNode.class);
        List<String> names = new ArrayList<>();
        if (r != null && r.has("items")) {
            r.get("items").forEach(i -> names.add(i.get("name").asText()));
        }
        return names;
    }

    public Optional<PathState> state(String name) {
        try {
            JsonNode p = http.get().uri("/v3/paths/get/{name}", name).retrieve().body(JsonNode.class);
            if (p == null) {
                return Optional.empty();
            }
            List<String> tracks = new ArrayList<>();
            if (p.has("tracks")) {
                p.get("tracks").forEach(t -> tracks.add(t.asText()));
            }
            int readers = p.has("readers") ? p.get("readers").size() : 0;
            return Optional.of(new PathState(name, p.path("ready").asBoolean(false), tracks, readers));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (RuntimeException e) {
            log.debug("Không đọc được trạng thái path {}: {}", name, e.toString());
            return Optional.empty();
        }
    }

    /** Chờ path có luồng (đã kéo được từ camera), tối đa {@code timeout}. */
    public Optional<PathState> awaitReady(String name, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<PathState> s = state(name).filter(PathState::ready);
            if (s.isPresent()) {
                return s;
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return Optional.empty();
    }
}
