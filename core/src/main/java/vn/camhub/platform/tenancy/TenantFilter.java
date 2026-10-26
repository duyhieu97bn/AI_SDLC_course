package vn.camhub.platform.tenancy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.web.filter.OncePerRequestFilter;

/** Đặt {@link OrganizationContext} cho mỗi yêu cầu HTTP và luôn xóa sau khi xử lý xong. */
public class TenantFilter extends OncePerRequestFilter {

    private final List<OrganizationResolver> resolvers;

    public TenantFilter(List<OrganizationResolver> resolvers) {
        this.resolvers = List.copyOf(resolvers);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            resolvers.stream()
                    .map(r -> r.resolve(request))
                    .flatMap(Optional::stream)
                    .findFirst()
                    .ifPresent(OrganizationContext::set);
            chain.doFilter(request, response);
        } finally {
            OrganizationContext.clear();
        }
    }
}
