package vn.camhub.access.domain;

import static vn.camhub.access.api.Permission.CONFIGURE_CAMERA;
import static vn.camhub.access.api.Permission.CONTROL_PTZ;
import static vn.camhub.access.api.Permission.EXPORT_VIDEO;
import static vn.camhub.access.api.Permission.HOLD_EVIDENCE;
import static vn.camhub.access.api.Permission.MANAGE_USERS;
import static vn.camhub.access.api.Permission.TALK_AUDIO;
import static vn.camhub.access.api.Permission.VIEW_LIVE;
import static vn.camhub.access.api.Permission.VIEW_PLAYBACK;

import java.util.EnumSet;
import java.util.Set;
import vn.camhub.access.api.Permission;

/**
 * 5 vai trò hệ thống (BRS 4.6, Use case mục 2). Không sửa, đổi tên hay xóa được (INV-20).
 * {@code rank} dùng cho khóa PTZ: người có rank cao hơn giành được quyền điều khiển (SADD 3.11.4).
 */
public enum SystemRole {
    ADMIN("Quản trị hệ thống", 100, EnumSet.allOf(Permission.class)),
    // Site Manager "quản lý camera và người dùng trong địa điểm" (BRS 4.6): có CONFIGURE_CAMERA để chuyển camera, và
    // TALK_AUDIO để tạo được Operator (INV-27 cấm gán vai trò có quyền mà người gán không có; US-32 AC3)
    SITE_MANAGER("Quản lý địa điểm", 80, EnumSet.of(VIEW_LIVE, VIEW_PLAYBACK, EXPORT_VIDEO, HOLD_EVIDENCE, CONTROL_PTZ,
            MANAGE_USERS, CONFIGURE_CAMERA, TALK_AUDIO)),
    TECHNICIAN("Kỹ thuật viên", 60, EnumSet.of(VIEW_LIVE, CONFIGURE_CAMERA, CONTROL_PTZ)),
    OPERATOR("Nhân viên giám sát", 40, EnumSet.of(VIEW_LIVE, VIEW_PLAYBACK, CONTROL_PTZ, TALK_AUDIO)),
    VIEWER("Người xem", 10, EnumSet.of(VIEW_LIVE));

    private final String displayName;
    private final int rank;
    private final Set<Permission> permissions;

    SystemRole(String displayName, int rank, Set<Permission> permissions) {
        this.displayName = displayName;
        this.rank = rank;
        this.permissions = Set.copyOf(permissions);
    }

    public String displayName() {
        return displayName;
    }

    public int rank() {
        return rank;
    }

    public Set<Permission> permissions() {
        return permissions;
    }

    public static java.util.Optional<SystemRole> ofCode(String code) {
        if (code == null) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(valueOf(code));
        } catch (IllegalArgumentException e) {
            return java.util.Optional.empty();
        }
    }
}
