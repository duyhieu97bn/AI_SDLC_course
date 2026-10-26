package vn.camhub.access.api;

/** Quyền nghiệp vụ (class diagram mục 6). Quyền luôn gắn với một phạm vi khi gán cho người dùng. */
public enum Permission {
    VIEW_LIVE,
    VIEW_PLAYBACK,
    EXPORT_VIDEO,
    CONTROL_PTZ,
    CONFIGURE_CAMERA,
    MANAGE_USERS,
    MANAGE_ROLES,
    MANAGE_SYSTEM,
    HOLD_EVIDENCE,
    TALK_AUDIO
}
