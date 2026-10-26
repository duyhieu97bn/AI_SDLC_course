package vn.camhub.device.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.camhub.device.api.DeviceControlPort.RecorderInfo;
import vn.camhub.device.application.MaintenanceService;
import vn.camhub.device.application.MaintenanceService.PasswordResult;
import vn.camhub.device.application.MaintenanceService.RebootStatus;
import vn.camhub.device.application.RecorderService;
import vn.camhub.device.application.RecorderService.ChannelChoice;
import vn.camhub.device.application.RecorderService.Connection;
import vn.camhub.device.application.RecorderService.Created;
import vn.camhub.device.infrastructure.RecorderRepository.Recorder;
import vn.camhub.platform.security.CurrentUser;

/** Đầu ghi NVR/DVR (US-03) và bảo trì camera (US-28) – SADD 3.7.1 nhóm "Thiết bị". */
@RestController
public class RecorderController {

    public record ProbeBody(@NotNull UUID siteId, @NotNull @Valid Connection connection) {
    }

    public record CreateBody(@NotNull UUID zoneId, String name, @NotNull @Valid Connection connection,
            @NotEmpty List<ChannelChoice> channels) {
    }

    public record PasswordBody(@NotEmpty List<UUID> cameraIds, @NotNull String newPassword) {
    }

    private final RecorderService recorders;
    private final MaintenanceService maintenance;

    public RecorderController(RecorderService recorders, MaintenanceService maintenance) {
        this.recorders = recorders;
        this.maintenance = maintenance;
    }

    @GetMapping("/api/v1/recorders")
    public List<Recorder> list() {
        return recorders.list(me());
    }

    @PostMapping("/api/v1/recorders/channels")
    public RecorderInfo channels(@Valid @RequestBody ProbeBody body) {
        return recorders.probe(me(), body.siteId(), body.connection());
    }

    @PostMapping("/api/v1/recorders")
    @ResponseStatus(HttpStatus.CREATED)
    public Created create(@Valid @RequestBody CreateBody body) {
        return recorders.create(me(), body.zoneId(), body.name(), body.connection(), body.channels());
    }

    @PostMapping("/api/v1/cameras/password")
    public List<PasswordResult> changePasswords(@Valid @RequestBody PasswordBody body) {
        return maintenance.changePasswords(me(), body.cameraIds(), body.newPassword());
    }

    @PostMapping("/api/v1/cameras/{id}/reboot")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RebootStatus reboot(@PathVariable UUID id) {
        return maintenance.reboot(me(), id);
    }

    @GetMapping("/api/v1/cameras/{id}/reboot")
    public RebootStatus rebootStatus(@PathVariable UUID id) {
        return maintenance.rebootStatus(me(), id);
    }

    private static UUID me() {
        return CurrentUser.require().userId();
    }
}
