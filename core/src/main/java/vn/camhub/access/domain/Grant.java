package vn.camhub.access.domain;

import java.util.Set;
import java.util.UUID;
import vn.camhub.access.api.Permission;
import vn.camhub.access.api.ScopeType;

/** Một gán vai trò đã giải ra tập quyền: dạng lưu trong cache quyền (SADD 3.11.1). */
public record Grant(UUID roleId, Set<Permission> permissions, ScopeType scopeType, UUID scopeId) {

    public Grant {
        permissions = Set.copyOf(permissions);
    }

    public boolean grants(Permission p) {
        return permissions.contains(p);
    }

    /** INV-10: phạm vi của gán này bao trùm camera ở {@code siteId} thuộc các nhóm {@code groupIds}. */
    public boolean covers(UUID cameraId, UUID siteId, Set<UUID> groupIds) {
        return switch (scopeType) {
            case ORGANIZATION -> true;
            case SITE -> scopeId.equals(siteId);
            case GROUP -> groupIds.contains(scopeId);
            case CAMERA -> scopeId.equals(cameraId);
        };
    }
}
