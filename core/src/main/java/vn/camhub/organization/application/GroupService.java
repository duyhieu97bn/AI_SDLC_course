package vn.camhub.organization.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.camhub.access.api.AccessApi;
import vn.camhub.access.api.Permission;
import vn.camhub.organization.api.CameraLocationPort;
import vn.camhub.organization.infrastructure.OrganizationRepository;
import vn.camhub.organization.infrastructure.OrganizationRepository.GroupRow;
import vn.camhub.platform.audit.AuditableEvent;
import vn.camhub.platform.security.ApiException;

/** Nhóm camera logic: một camera thuộc nhiều nhóm (class diagram mục 3). */
@Service
public class GroupService {

    private final OrganizationRepository repo;
    private final AccessApi access;
    private final CameraLocationPort cameras;
    private final ApplicationEventPublisher events;

    public GroupService(OrganizationRepository repo, AccessApi access, CameraLocationPort cameras,
            ApplicationEventPublisher events) {
        this.repo = repo;
        this.access = access;
        this.cameras = cameras;
        this.events = events;
    }

    public List<GroupRow> list() {
        return repo.groups();
    }

    public List<UUID> members(UUID groupId) {
        return repo.groupMembers(groupId);
    }

    @Transactional
    public UUID create(UUID actor, String name, String description) {
        requireConfigure(actor);
        UUID id = repo.insertGroup(name.trim(), description);
        audit(actor, id, Map.of("operation", "CREATE_GROUP", "name", name));
        return id;
    }

    @Transactional
    public void update(UUID actor, UUID id, String name, String description) {
        requireConfigure(actor);
        if (repo.updateGroup(id, name, description) == 0) {
            throw ApiException.notFound("nhóm camera");
        }
        audit(actor, id, Map.of("operation", "UPDATE_GROUP"));
    }

    @Transactional
    public void delete(UUID actor, UUID id) {
        requireConfigure(actor);
        repo.deleteGroup(id);
        audit(actor, id, Map.of("operation", "DELETE_GROUP"));
    }

    /** Thêm/bớt camera; người sửa phải có CONFIGURE_CAMERA trên từng camera. */
    @Transactional
    public void setMembers(UUID actor, UUID groupId, List<UUID> add, List<UUID> remove) {
        requireConfigure(actor);
        for (UUID c : add) {
            requireCamera(actor, c);
            repo.addMember(groupId, c);
        }
        for (UUID c : remove) {
            requireCamera(actor, c);
            repo.removeMember(groupId, c);
        }
        audit(actor, groupId, Map.of("operation", "SET_GROUP_MEMBERS", "added", add.size(), "removed", remove.size()));
    }

    private void requireConfigure(UUID actor) {
        if (!access.hasAnywhere(actor, Permission.CONFIGURE_CAMERA)) {
            throw ApiException.forbidden();
        }
    }

    private void requireCamera(UUID actor, UUID cameraId) {
        cameras.findLocation(cameraId).orElseThrow(() -> ApiException.notFound("camera"));
        if (!access.canOnCamera(actor, Permission.CONFIGURE_CAMERA, cameraId)) {
            throw ApiException.forbidden();
        }
    }

    private void audit(UUID actor, UUID id, Map<String, Object> details) {
        events.publishEvent(AuditableEvent.of(actor, "CHANGE_CONFIG", "CAMERA_GROUP", id.toString(), details));
    }
}
