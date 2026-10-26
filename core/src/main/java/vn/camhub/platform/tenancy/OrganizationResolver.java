package vn.camhub.platform.tenancy;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;

/**
 * Xác định Tổ chức của một yêu cầu HTTP. S1 thay bản dev bằng bản đọc claim {@code org} trong JWT.
 */
@FunctionalInterface
public interface OrganizationResolver {

    Optional<UUID> resolve(HttpServletRequest request);
}
