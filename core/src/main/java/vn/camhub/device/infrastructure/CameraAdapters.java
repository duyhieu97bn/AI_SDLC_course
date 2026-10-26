package vn.camhub.device.infrastructure;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.camhub.access.api.CameraScopeLookup;
import vn.camhub.access.api.ScopeFilter;
import vn.camhub.organization.api.CameraLocationPort;

/** Hiện thực các cổng mà module access và organization cần từ module device (đảo phụ thuộc, SADD 3.4). */
@Component
public class CameraAdapters implements CameraScopeLookup, CameraLocationPort {

    private final CameraRepository cameras;

    public CameraAdapters(CameraRepository cameras) {
        this.cameras = cameras;
    }

    // ---------------------------------------------------------------- CameraScopeLookup (access)

    @Override
    public Optional<CameraScope> find(UUID cameraId) {
        return cameras.find(cameraId).map(c -> new CameraScope(c.id(), c.siteId(), c.groupIds()));
    }

    @Override
    public Map<UUID, CameraScope> findAll(Collection<UUID> cameraIds) {
        Map<UUID, CameraScope> out = new HashMap<>();
        cameras.findAll(cameraIds).forEach((id, c) -> out.put(id, new CameraScope(id, c.siteId(), c.groupIds())));
        return out;
    }

    @Override
    public Set<UUID> sitesOfGroup(UUID groupId) {
        return cameras.sitesOfGroup(groupId);
    }

    // ---------------------------------------------------------------- CameraLocationPort (organization)

    @Override
    public Optional<Location> findLocation(UUID cameraId) {
        return cameras.find(cameraId).map(c -> new Location(c.id(), c.name(), c.siteId(), c.zoneId()));
    }

    @Override
    public void relocate(UUID cameraId, UUID siteId, UUID zoneId) {
        cameras.relocate(cameraId, siteId, zoneId);
    }

    @Override
    public Set<UUID> sitesWithCameras(ScopeFilter filter) {
        return cameras.sitesWithCameras(filter);
    }

    @Override
    public long countInZone(UUID zoneId) {
        return cameras.countInZone(zoneId);
    }
}
