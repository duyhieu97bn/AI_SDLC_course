package vn.camhub.configuration.web;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import vn.camhub.configuration.application.ImageConfigService;
import vn.camhub.configuration.application.NetworkConfigService;
import vn.camhub.configuration.application.ZoneConfigService;
import vn.camhub.device.api.DeviceControlPort.ZonesConfig;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.camhub.configuration.application.StreamConfigService;
import vn.camhub.configuration.application.StreamConfigService.ApplyResult;
import vn.camhub.device.api.DeviceControlPort.EncoderChange;
import vn.camhub.device.api.DeviceControlPort.EncoderConfig;
import vn.camhub.platform.security.CurrentUser;

/** GET/PUT /cameras/{id}/config/stream (US-23), /config/zones (US-26) – SADD 3.7.1 nhóm "Cấu hình". */
@RestController
@RequestMapping("/api/v1/cameras/{id}/config")
public class CameraConfigController {

    public record StreamChanges(@NotEmpty List<EncoderChange> changes) {
    }

    public record MaskBody(@NotNull List<ZoneConfigService.MaskChange> items) {
    }

    private final StreamConfigService streams;
    private final ZoneConfigService zones;
    private final ImageConfigService images;
    private final NetworkConfigService networks;

    public CameraConfigController(StreamConfigService streams, ZoneConfigService zones, ImageConfigService images,
            NetworkConfigService networks) {
        this.networks = networks;
        this.streams = streams;
        this.zones = zones;
        this.images = images;
    }

    /** Mạng, nguồn giờ, độ lệch đồng hồ (US-25). */
    @GetMapping("/network")
    public vn.camhub.device.api.DeviceControlPort.NetworkInfo network(@PathVariable UUID id) {
        return networks.get(CurrentUser.require().userId(), id);
    }

    @PutMapping("/network")
    public vn.camhub.device.api.DeviceControlPort.NetworkSetResult setNetwork(@PathVariable UUID id,
            @RequestBody vn.camhub.device.api.DeviceControlPort.NetworkChange body) {
        return networks.setNetwork(CurrentUser.require().userId(), id, body);
    }

    @PutMapping("/time")
    public vn.camhub.device.api.DeviceControlPort.NetworkInfo setTime(@PathVariable UUID id,
            @RequestBody vn.camhub.device.api.DeviceControlPort.TimeChange body) {
        return networks.setTime(CurrentUser.require().userId(), id, body);
    }

    /** Thông số hình ảnh và khoảng giá trị camera hỗ trợ (US-24). */
    @GetMapping("/image")
    public vn.camhub.device.api.DeviceControlPort.ImagingSettings image(@PathVariable UUID id) {
        return images.get(CurrentUser.require().userId(), id);
    }

    @PutMapping("/image")
    public vn.camhub.device.api.DeviceControlPort.ImagingSettings setImage(@PathVariable UUID id,
            @RequestBody ImageConfigService.ImageChange body) {
        return images.apply(CurrentUser.require().userId(), id, body);
    }

    /** Vùng chuyển động và vùng che hiện tại cùng giới hạn của camera (US-26). */
    @GetMapping("/zones")
    public ZonesConfig zones(@PathVariable UUID id) {
        return zones.get(CurrentUser.require().userId(), id);
    }

    @PutMapping("/zones/motion")
    public ZonesConfig motion(@PathVariable UUID id, @RequestBody ZoneConfigService.MotionChange body) {
        return zones.setMotion(CurrentUser.require().userId(), id, body);
    }

    @PutMapping("/zones/masks")
    public ZonesConfig masks(@PathVariable UUID id, @Valid @RequestBody MaskBody body) {
        return zones.setMasks(CurrentUser.require().userId(), id, body.items());
    }

    @GetMapping("/stream")
    public List<EncoderConfig> stream(@PathVariable UUID id) {
        return streams.get(CurrentUser.require().userId(), id);
    }

    @PutMapping("/stream")
    public ApplyResult applyStream(@PathVariable UUID id, @RequestBody StreamChanges body) {
        return streams.apply(CurrentUser.require().userId(), id, body.changes());
    }
}
