package vn.camhub.device.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Danh sách theo dõi kết nối (US-04) và đăng ký sự kiện PullPoint (US-36) trên Device Gateway. Gateway chỉ giữ trong
 * bộ nhớ nên Core gửi lại định kỳ.
 */
@Component
public class GatewayMonitorClient {

    private final RestClient http;

    public GatewayMonitorClient(GatewayClient.GatewayProps props, RestClient.Builder builder, SslBundles ssl) {
        this.http = GatewayClient.build(props, builder, ssl);
    }

    public void replaceTargets(List<Map<String, Object>> targets, int intervalSeconds) {
        http.put().uri("/v1/monitor/targets").body(Map.of("targets", targets, "intervalSeconds", intervalSeconds))
                .retrieve().toBodilessEntity();
    }

    /** Camera đang có đăng ký sự kiện trên Gateway. */
    public Set<String> subscribedCameras() {
        JsonNode list = http.get().uri("/v1/events/subscriptions").retrieve().body(JsonNode.class);
        Set<String> ids = new HashSet<>();
        if (list != null) {
            list.forEach(n -> {
                if (!"STOPPED".equals(n.path("status").asText())) {
                    ids.add(n.path("cameraId").asText());
                }
            });
        }
        return ids;
    }

    /** Đăng ký nhận sự kiện (chuyển động) của camera; Gateway tự gia hạn và kết nối lại. */
    public void subscribe(String cameraId, Map<String, Object> device) {
        http.post().uri("/v1/events/subscriptions").body(Map.of("cameraId", cameraId, "device", device)).retrieve()
                .toBodilessEntity();
    }

    public void unsubscribe(String cameraId) {
        http.delete().uri("/v1/events/subscriptions/{id}", cameraId).retrieve().toBodilessEntity();
    }
}
