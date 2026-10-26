package vn.camhub.identity.api;

import java.util.Optional;
import java.util.UUID;

/** API công khai của module identity. */
public interface IdentityApi {

    /** Tạo tài khoản cục bộ không qua kiểm tra quyền: Admin đầu tiên khi khởi tạo Tổ chức, dữ liệu demo. */
    UUID createLocalUser(String email, String displayName, String password);

    Optional<String> displayName(UUID userId);

    /** Email của người dùng đang hoạt động (gửi thông báo); rỗng nếu tài khoản bị khóa/vô hiệu. */
    Optional<String> email(UUID userId);
}
