package vn.camhub.demo;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import vn.camhub.access.api.AccessApi;
import vn.camhub.access.api.ScopeType;
import vn.camhub.identity.api.IdentityApi;
import vn.camhub.platform.crypto.CryptoService;
import vn.camhub.platform.tenancy.OrganizationContext;

/**
 * Dữ liệu demo cho môi trường dev (profile {@code demo}), chỉ chạy khi Tổ chức chưa có địa điểm nào:
 * 3 địa điểm, mỗi địa điểm 2–3 khu vực; camera RTSP trỏ vào máy chủ camera giả lập {@code rtsp://camera-sim:8554/sim-N};
 * mỗi vai trò hệ thống một tài khoản theo persona của PRD (Lan, Minh, Hương...).
 * Không bao giờ bật ở staging/production.
 */
@Component
@Profile("demo")
@Order(10)
public class DemoDataSeeder implements ApplicationRunner {

    @ConfigurationProperties("camhub.demo")
    public record DemoProps(String password, int cameras) {
        public DemoProps {
            if (password == null || password.isBlank()) {
                password = "Demo-Pass-2026";
            }
            if (cameras <= 0) {
                cameras = 20;
            }
        }
    }

    record SiteSpec(String name, String address, List<String> zones) {
    }

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    private static final List<SiteSpec> SITES = List.of(
            new SiteSpec("Kho Bắc Ninh", "KCN Quế Võ, Bắc Ninh", List.of("Cổng chính", "Kho hàng", "Bãi xe")),
            new SiteSpec("Chi nhánh Hà Nội", "Cầu Giấy, Hà Nội", List.of("Sảnh", "Quầy giao dịch")),
            new SiteSpec("Cửa hàng Đà Nẵng", "Hải Châu, Đà Nẵng", List.of("Mặt tiền", "Kho cửa hàng")));

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final AccessApi access;
    private final IdentityApi identity;
    private final DemoProps props;
    private final String adminEmail;
    private final CryptoService crypto;

    public DemoDataSeeder(JdbcClient jdbc, TransactionTemplate tx, AccessApi access, IdentityApi identity,
            DemoProps props, @Value("${camhub.bootstrap.admin-email}") String adminEmail, CryptoService crypto) {
        this.crypto = crypto;
        this.adminEmail = adminEmail;
        this.jdbc = jdbc;
        this.tx = tx;
        this.access = access;
        this.identity = identity;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Tổ chức đầu tiên (do OrganizationBootstrap tạo); tra qua tài khoản admin đầu tiên
        List<UUID> orgs = jdbc.sql("select organization_id from camhub_login_lookup(?)")
                .param(adminEmail).query(UUID.class).list();
        if (orgs.isEmpty()) {
            log.warn("Demo: chưa có Tổ chức, bỏ qua");
            return;
        }
        UUID org = orgs.get(0);
        OrganizationContext.runAs(org, () -> tx.executeWithoutResult(s -> seed(org)));
    }

    private void seed(UUID org) {
        if (jdbc.sql("select count(*) from site").query(Long.class).single() > 0) {
            return;
        }
        int cam = 0;
        UUID firstSite = null;
        UUID secondSite = null;
        UUID group = UUID.randomUUID();
        jdbc.sql("insert into camera_group (id, organization_id, name, description) values (?, ?, 'Lối ra vào', 'Mọi camera cổng và sảnh')")
                .params(group, org).update();
        for (int si = 0; si < SITES.size(); si++) {
            SiteSpec spec = SITES.get(si);
            UUID site = UUID.randomUUID();
            jdbc.sql("insert into site (id, organization_id, name, address) values (?, ?, ?, ?)")
                    .params(site, org, spec.name(), spec.address()).update();
            if (si == 0) {
                firstSite = site;
            } else if (si == 1) {
                secondSite = site;
            }
            for (int zi = 0; zi < spec.zones().size(); zi++) {
                UUID zone = UUID.randomUUID();
                jdbc.sql("insert into zone (id, organization_id, site_id, name) values (?, ?, ?, ?)")
                        .params(zone, org, site, spec.zones().get(zi)).update();
                int perZone = (int) Math.ceil(props.cameras() / 7.0); // 7 khu vực demo
                for (int k = 0; k < perZone && cam < props.cameras(); k++) {
                    cam++;
                    UUID id = UUID.randomUUID();
                    // Camera giả lập: máy chủ RTSP camera-sim (deploy/camera-sim), tài khoản như camera thật
                    jdbc.sql("""
                            insert into camera (id, organization_id, site_id, zone_id, name, protocol, ip_address, port,
                                                stream_url, username, password_enc, manufacturer, model, status, verified)
                            values (?, ?, ?, ?, ?, 'RTSP', 'camera-sim', 8554, ?, 'admin', ?, 'Giả lập', 'RTSP-SIM 720p',
                                    'UNKNOWN', true)
                            """).params(id, org, site, zone, spec.zones().get(zi) + " " + (k + 1),
                                    "rtsp://camera-sim:8554/sim-" + cam, crypto.encrypt(org, "admin123")).update();
                    jdbc.sql("insert into camera_location_history (organization_id, camera_id, site_id, zone_id) values (?, ?, ?, ?)")
                            .params(org, id, site, zone).update();
                    // Luồng chính và luồng phụ (camera giả lập chỉ có một luồng 720p, dùng chung cho cả hai)
                    for (String type : List.of("MAIN", "SUB")) {
                        jdbc.sql("""
                                insert into camera_stream (organization_id, camera_id, stream_type, codec, width, height,
                                                           fps, rtsp_url)
                                values (?, ?, ?, 'H264', 1280, 720, 25, ?)
                                """).params(org, id, type, "rtsp://camera-sim:8554/sim-" + cam).update();
                    }
                    if (zi == 0) {
                        jdbc.sql("insert into camera_group_member (organization_id, group_id, camera_id) values (?, ?, ?)")
                                .params(org, group, id).update();
                    }
                }
            }
        }
        UUID siteManagerRole = role("SITE_MANAGER");
        user("huong@camhub.local", "Nguyễn Thu Hương", siteManagerRole, ScopeType.SITE, secondSite);
        user("lan@camhub.local", "Trần Thị Lan", role("OPERATOR"), ScopeType.SITE, firstSite);
        user("minh@camhub.local", "Lê Văn Minh", role("TECHNICIAN"), ScopeType.ORGANIZATION, null);
        user("viewer@camhub.local", "Phạm Người Xem", role("VIEWER"), ScopeType.GROUP, group);
        log.info("Demo: đã tạo {} địa điểm, {} camera và 4 tài khoản demo (mật khẩu camhub.demo.password)",
                SITES.size(), cam);
    }

    private UUID role(String code) {
        return jdbc.sql("select id from role where code = ?").param(code).query(UUID.class).single();
    }

    private void user(String email, String name, UUID roleId, ScopeType scope, UUID scopeId) {
        UUID id = identity.createLocalUser(email, name, props.password());
        access.assignUnchecked(id, roleId, scope, scopeId, "MANUAL");
    }
}
