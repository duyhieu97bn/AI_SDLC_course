package vn.camhub.device.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.camhub.device.api.DeviceControlPort.DiscoveredDevice;
import vn.camhub.device.application.ConnectorService;
import vn.camhub.device.application.ConnectorService.Activation;
import vn.camhub.device.application.ConnectorService.ConnectorView;
import vn.camhub.device.application.ConnectorService.Created;
import vn.camhub.device.application.ConnectorService.Heartbeat;
import vn.camhub.device.application.ConnectorService.HeartbeatReply;
import vn.camhub.platform.security.CurrentUser;
import vn.camhub.platform.tenancy.OrganizationContext;

/**
 * Bộ kết nối địa điểm (US-06): API quản trị cho Admin và API cho chính Site Connector
 * ({@code /api/v1/connector-agent/**}, không dùng access token người dùng).
 */
@RestController
public class ConnectorController {

    public record CreateBody(@NotNull UUID siteId, String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ActivateBody(@NotBlank String code, String hostname, String version) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TaskResultBody(List<DiscoveredDevice> devices, String error) {
    }

    private final ConnectorService connectors;

    public ConnectorController(ConnectorService connectors) {
        this.connectors = connectors;
    }

    @GetMapping("/api/v1/connectors")
    public List<ConnectorView> list() {
        return connectors.list(me());
    }

    @PostMapping("/api/v1/connectors")
    @ResponseStatus(HttpStatus.CREATED)
    public Created create(@Valid @RequestBody CreateBody body) {
        return connectors.create(me(), body.siteId(), body.name());
    }

    @PostMapping("/api/v1/connectors/{id}/activation-code")
    public Created newCode(@PathVariable UUID id) {
        return connectors.newCode(me(), id);
    }

    @DeleteMapping("/api/v1/connectors/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID id) {
        connectors.revoke(me(), id);
    }

    // ---------------------------------------------------------------- API của Site Connector

    @PostMapping("/api/v1/connector-agent/activate")
    public Activation activate(@Valid @RequestBody ActivateBody body) {
        // Địa chỉ Connector thấy (sau Nginx: scheme từ X-Forwarded-Proto, host:cổng từ Host)
        String origin = org.springframework.web.servlet.support.ServletUriComponentsBuilder.fromCurrentContextPath()
                .build().toUriString();
        return connectors.activate(body.code(), body.hostname(), body.version(), origin);
    }

    @PostMapping("/api/v1/connector-agent/heartbeat")
    public HeartbeatReply heartbeat(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @RequestBody Heartbeat body) {
        UUID org = connectors.authenticate(auth);
        UUID id = connectorId(auth);
        return OrganizationContext.callAs(org, () -> connectors.heartbeat(id, body));
    }

    @PostMapping("/api/v1/connector-agent/tasks/{taskId}/result")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void taskResult(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @PathVariable UUID taskId, @RequestBody TaskResultBody body) {
        UUID org = connectors.authenticate(auth);
        UUID id = connectorId(auth);
        OrganizationContext.runAs(org, () -> connectors.taskResult(id, taskId, body.devices(), body.error()));
    }

    /** Đã kiểm tra bởi {@link ConnectorService#authenticate}. */
    private static UUID connectorId(String auth) {
        String decoded = new String(java.util.Base64.getDecoder().decode(auth.substring(6).trim()),
                java.nio.charset.StandardCharsets.UTF_8);
        return UUID.fromString(decoded.substring(0, decoded.indexOf(':')));
    }

    private static UUID me() {
        return CurrentUser.require().userId();
    }
}
