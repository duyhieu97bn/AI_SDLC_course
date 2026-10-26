package vn.camhub.platform.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.camhub.platform.tenancy.OrganizationResolver;

/** Tổ chức của yêu cầu = claim {@code org} trong access token đã được Spring Security xác thực. */
@Component
public class JwtOrganizationResolver implements OrganizationResolver {

    @Override
    public Optional<UUID> resolve(HttpServletRequest request) {
        return CurrentUser.current().map(CurrentUser::organizationId);
    }
}
