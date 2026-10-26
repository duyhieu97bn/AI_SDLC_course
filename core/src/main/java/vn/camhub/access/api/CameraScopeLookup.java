package vn.camhub.access.api;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Cổng để module access biết camera thuộc địa điểm và nhóm nào. Module device hiện thực, nên access không
 * phụ thuộc device (tránh vòng phụ thuộc, SADD 3.4).
 */
public interface CameraScopeLookup {

    /** Vị trí hiện tại của camera (INV-18: quyền tính theo vị trí hiện tại). */
    record CameraScope(UUID cameraId, UUID siteId, Set<UUID> groupIds) {
    }

    Optional<CameraScope> find(UUID cameraId);

    Map<UUID, CameraScope> findAll(Collection<UUID> cameraIds);

    /** Địa điểm của mọi camera trong nhóm. */
    Set<UUID> sitesOfGroup(UUID groupId);
}
