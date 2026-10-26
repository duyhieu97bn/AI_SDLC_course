package vn.camhub.identity.application;

import java.util.Comparator;
import java.util.List;
import vn.camhub.access.api.AccessApi.RoleRef;

/** Trang mặc định sau đăng nhập theo vai trò cao nhất (US-30 AC1). Vai trò tùy chỉnh vào trang xem trực tiếp. */
final class LandingPage {

    private LandingPage() {
    }

    static String forRoles(List<RoleRef> roles) {
        return roles.stream().max(Comparator.comparingInt(RoleRef::rank)).map(r -> switch (r.code() == null ? "" : r.code()) {
            case "ADMIN", "SITE_MANAGER" -> "/dashboard";
            case "TECHNICIAN" -> "/cameras";
            default -> "/live";
        }).orElse("/live");
    }
}
