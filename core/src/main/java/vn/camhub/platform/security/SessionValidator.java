package vn.camhub.platform.security;

/**
 * Kiểm tra phiên của access token còn hiệu lực (chưa thu hồi, người dùng chưa bị khóa/vô hiệu hóa).
 * Module identity hiện thực; platform chỉ biết interface để không phụ thuộc module nghiệp vụ.
 */
public interface SessionValidator {

    boolean isActive(CurrentUser user);
}
