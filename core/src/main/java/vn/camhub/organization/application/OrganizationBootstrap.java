package vn.camhub.organization.application;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import vn.camhub.access.api.AccessApi;
import vn.camhub.access.api.ScopeType;
import vn.camhub.identity.api.IdentityApi;
import vn.camhub.organization.infrastructure.OrganizationRepository;
import vn.camhub.platform.tenancy.OrganizationContext;

/**
 * Lần khởi động đầu tiên (DB chưa có Tổ chức nào): tạo Tổ chức mặc định, 5 vai trò hệ thống và tài khoản Admin
 * đầu tiên. Bản 1.0 nội bộ chỉ có một Tổ chức; SaaS sau này tạo Tổ chức qua luồng đăng ký.
 */
@Component
@Order(0)
public class OrganizationBootstrap implements ApplicationRunner {

    @ConfigurationProperties("camhub.bootstrap")
    public record BootstrapProps(String organizationCode, String organizationName, String adminEmail,
            String adminPassword) {
    }

    private static final Logger log = LoggerFactory.getLogger(OrganizationBootstrap.class);

    private final OrganizationRepository repo;
    private final AccessApi access;
    private final IdentityApi identity;
    private final TransactionTemplate tx;
    private final BootstrapProps props;

    public OrganizationBootstrap(OrganizationRepository repo, AccessApi access, IdentityApi identity,
            TransactionTemplate tx, BootstrapProps props) {
        this.repo = repo;
        this.access = access;
        this.identity = identity;
        this.tx = tx;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (repo.organizationCount() > 0) {
            return;
        }
        if (props.adminPassword() == null || props.adminPassword().isBlank()) {
            log.warn("Chưa có Tổ chức nào và chưa cấu hình camhub.bootstrap.admin-password: bỏ qua khởi tạo");
            return;
        }
        UUID orgId = UUID.randomUUID();
        OrganizationContext.runAs(orgId, () -> tx.executeWithoutResult(s -> {
            repo.insertOrganization(orgId, props.organizationCode(), props.organizationName());
            UUID adminRole = access.provisionSystemRoles(orgId);
            UUID admin = identity.createLocalUser(props.adminEmail(), "Quản trị hệ thống", props.adminPassword());
            access.assignUnchecked(admin, adminRole, ScopeType.ORGANIZATION, null, "MANUAL");
        }));
        log.info("Đã khởi tạo Tổ chức {} và tài khoản Admin {}", props.organizationCode(), props.adminEmail());
    }
}
