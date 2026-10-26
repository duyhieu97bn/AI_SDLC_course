package vn.camhub.system.web;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.camhub.alerting.api.AlertingApi;
import vn.camhub.device.api.DeviceApi;
import vn.camhub.device.api.DeviceApi.CameraState;
import vn.camhub.organization.api.OrganizationApi;
import vn.camhub.platform.security.CurrentUser;
import vn.camhub.platform.storage.ObjectStorage;
import vn.camhub.recording.api.RecordingApi;
import vn.camhub.recording.api.RecordingApi.StorageVolume;
import vn.camhub.system.application.BackupStatusReader;

/**
 * Bảng tổng quan hệ thống (US-39) và trạng thái sao lưu chỉ xem (US-40.3), cho Admin (MANAGE_SYSTEM toàn Tổ chức).
 * Tổng hợp từ các module qua API công khai; bộ kết nối địa điểm có từ S6.
 */
@RestController
public class SystemController {

    public record OfflineCamera(UUID id, String name, String siteName, Instant since) {
    }

    public record Overview(int camerasOnline, int camerasTotal, List<OfflineCamera> offlineCameras,
            long openAlerts, List<StorageVolume> storage, boolean objectStorageUp, List<Object> connectors,
            JsonNode backup) {
    }

    private final DeviceApi devices;
    private final OrganizationApi organizations;
    private final AlertingApi alerting;
    private final RecordingApi recordings;
    private final ObjectStorage objects;
    private final BackupStatusReader backups;

    public SystemController(DeviceApi devices, OrganizationApi organizations, AlertingApi alerting,
            RecordingApi recordings, ObjectStorage objects, BackupStatusReader backups) {
        this.devices = devices;
        this.organizations = organizations;
        this.alerting = alerting;
        this.recordings = recordings;
        this.objects = objects;
        this.backups = backups;
    }

    @GetMapping("/api/v1/system/overview")
    @PreAuthorize("hasPermission(null, 'ORGANIZATION', 'MANAGE_SYSTEM')")
    public Overview overview() {
        List<CameraState> states = devices.states();
        int online = (int) states.stream().filter(s -> "ONLINE".equals(s.status())).count();
        List<OfflineCamera> offline = states.stream().filter(s -> "OFFLINE".equals(s.status()))
                .sorted(Comparator.comparing(CameraState::statusSince, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(s -> new OfflineCamera(s.cameraId(), s.name(), organizations.siteName(s.siteId()).orElse(""),
                        s.statusSince()))
                .toList();
        return new Overview(online, states.size(), offline, alerting.openCount(CurrentUser.require().userId()),
                recordings.storageVolumes(), objects.healthy(), List.of(), backups.read());
    }

    @GetMapping("/api/v1/system/backups")
    @PreAuthorize("hasPermission(null, 'ORGANIZATION', 'MANAGE_SYSTEM')")
    public JsonNode backups() {
        return backups.read();
    }
}
