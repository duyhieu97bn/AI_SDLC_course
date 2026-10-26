package vn.camhub.platform.tenancy;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;

/**
 * Chỉ dùng cho dev/PoC khi chưa có đăng nhập (S0): lấy Tổ chức từ header {@value #HEADER}.
 * Bật bằng {@code camhub.tenancy.header-resolver-enabled=true}; tắt ở staging/production.
 */
public class HeaderOrganizationResolver implements OrganizationResolver {

    public static final String HEADER = "X-CamHub-Org";

    @Override
    public Optional<UUID> resolve(HttpServletRequest request) {
        String value = request.getHeader(HEADER);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value.trim()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
