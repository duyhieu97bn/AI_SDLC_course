package vn.camhub.device.application;

import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import vn.camhub.device.domain.Connector;
import vn.camhub.device.domain.RtspUrls;
import vn.camhub.device.infrastructure.ConnectorRepository;

/**
 * Định tuyến tới camera sau NAT qua đường hầm của bộ kết nối địa điểm (ADR-10, TS-06.3):
 * <ul>
 *   <li>ONVIF (Device Gateway): proxy SOCKS5 ngược {@code socks5h://tunnel:<socksPort>}.</li>
 *   <li>RTSP (MediaMTX không dùng được SOCKS): cổng chuyển tiếp ngược {@code tunnel:<port> → host:targetPort}, cấp
 *       khi cần; Connector nhận cấu hình mới ở heartbeat kế tiếp (≤ 30 giây).</li>
 * </ul>
 */
@Component
public class TunnelRouting {

    /** Cấu hình chuyển tiếp của bộ kết nối đã đổi: ghi lại authfile của Tunnel Server. */
    public record ForwardsChanged(UUID connectorId) {
    }

    public record HostPort(String host, int port) {
    }

    private final ConnectorRepository connectors;
    private final ApplicationEventPublisher events;
    private final String tunnelHost;

    public TunnelRouting(ConnectorRepository connectors, ApplicationEventPublisher events,
            @Value("${camhub.tunnel.host:tunnel}") String tunnelHost) {
        this.connectors = connectors;
        this.events = events;
        this.tunnelHost = tunnelHost;
    }

    /** Bộ kết nối đã kích hoạt của địa điểm (camera thêm vào địa điểm này đi qua đường hầm). */
    public Optional<UUID> connectorForSite(UUID siteId) {
        return siteId == null ? Optional.empty() : connectors.activeForSite(siteId).map(Connector::id);
    }

    /** URL proxy cho Device Gateway; null nếu không qua đường hầm. */
    public String proxy(UUID connectorId) {
        if (connectorId == null) {
            return null;
        }
        return connectors.find(connectorId).map(Connector::socksPort)
                .map(p -> "socks5h://" + tunnelHost + ":" + p).orElse(null);
    }

    /** URL RTSP mà MediaMTX dùng: thay máy:cổng trong LAN địa điểm bằng cổng chuyển tiếp trên Tunnel Server. */
    public String rtsp(UUID connectorId, String rtspUrl) {
        if (connectorId == null || rtspUrl == null) {
            return rtspUrl;
        }
        RtspUrls.Parsed p = RtspUrls.parse(rtspUrl);
        HostPort hp = forward(connectorId, p.host(), p.port());
        return RtspUrls.withHostPort(p.urlWithoutCredentials(), hp.host(), hp.port());
    }

    /** Cổng chuyển tiếp tới {@code host:targetPort} tại địa điểm; cấp mới nếu chưa có. */
    public HostPort forward(UUID connectorId, String host, int targetPort) {
        Optional<Integer> existing = connectors.forwardPort(connectorId, host, targetPort);
        if (existing.isPresent()) {
            return new HostPort(tunnelHost, existing.get());
        }
        for (int attempt = 0; attempt < 50; attempt++) {
            int port = Connector.FORWARD_PORT_MIN
                    + java.util.concurrent.ThreadLocalRandom.current().nextInt(
                            Connector.FORWARD_PORT_MAX - Connector.FORWARD_PORT_MIN + 1);
            try {
                connectors.insertForward(connectorId, host, targetPort, port);
                events.publishEvent(new ForwardsChanged(connectorId));
                return new HostPort(tunnelHost, port);
            } catch (DuplicateKeyException e) {
                // Cổng đã dùng (của bộ kết nối khác) hoặc vừa có luồng khác cấp cùng đích: thử lại
                Optional<Integer> raced = connectors.forwardPort(connectorId, host, targetPort);
                if (raced.isPresent()) {
                    return new HostPort(tunnelHost, raced.get());
                }
            }
        }
        throw new IllegalStateException("Hết cổng chuyển tiếp cho đường hầm");
    }
}
