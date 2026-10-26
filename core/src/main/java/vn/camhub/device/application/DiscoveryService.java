package vn.camhub.device.application;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.camhub.access.api.AccessApi;
import vn.camhub.access.api.Permission;
import vn.camhub.device.api.DeviceControlPort;
import vn.camhub.device.api.DeviceControlPort.DeviceException;
import vn.camhub.device.infrastructure.CameraRepository;
import vn.camhub.platform.security.ApiException;

/**
 * Dò tìm camera ONVIF (US-01, UC-10). Device Gateway dò WS-Discovery trong mạng nó thấy được; địa điểm sau NAT
 * dò qua Site Connector (S6). Thiết bị đã có trong hệ thống được nhận diện theo MAC, nếu không có MAC thì theo IP.
 */
@Service
public class DiscoveryService {

    public record Found(String host, int port, String manufacturer, String model, String macAddress, String hardware,
            boolean alreadyAdded) {
    }

    static final int TIMEOUT_SECONDS = 30;
    static final int CONNECTOR_TIMEOUT_SECONDS = 5;

    private final DeviceControlPort devices;
    private final CameraRepository cameras;
    private final AccessApi access;
    private final TunnelRouting routing;
    private final ConnectorService connectors;

    public DiscoveryService(DeviceControlPort devices, CameraRepository cameras, AccessApi access,
            TunnelRouting routing, ConnectorService connectors) {
        this.routing = routing;
        this.connectors = connectors;
        this.devices = devices;
        this.cameras = cameras;
        this.access = access;
    }

    public List<Found> discover(UUID actor, UUID siteId, List<String> targets) {
        if (!access.canOnSite(actor, Permission.CONFIGURE_CAMERA, siteId)) {
            throw ApiException.forbidden();
        }
        List<DeviceControlPort.DiscoveredDevice> found;
        var connector = routing.connectorForSite(siteId);
        try {
            // Địa điểm sau NAT: Connector dò WS-Discovery trong LAN địa điểm (multicast không đi qua đường hầm)
            found = connector.isPresent() ? connectors.discover(connector.get(), CONNECTOR_TIMEOUT_SECONDS)
                    : devices.discover(targets, TIMEOUT_SECONDS);
        } catch (DeviceException e) {
            throw new ApiException(502, e.code(), CameraService.deviceMessage(e));
        } catch (DeviceControlPort.GatewayUnavailableException
                 | io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
            throw new ApiException(503, "GATEWAY_UNAVAILABLE", "Không kết nối được Device Gateway");
        }
        Set<String> macs = cameras.knownMacs();
        Set<String> hosts = cameras.knownHosts();
        return found.stream().map(d -> new Found(d.host(), d.port(), d.manufacturer(), d.model(), d.macAddress(),
                d.hardware(), (d.macAddress() != null && macs.contains(normalizeMac(d.macAddress())))
                        || hosts.contains(d.host())))
                .toList();
    }

    static String normalizeMac(String mac) {
        return mac.replaceAll("[^0-9a-fA-F]", "").toLowerCase();
    }
}
