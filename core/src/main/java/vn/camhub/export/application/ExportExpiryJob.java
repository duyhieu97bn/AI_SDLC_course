package vn.camhub.export.application;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vn.camhub.platform.tenancy.OrganizationContext;

/** Mỗi 10 phút: clip READY quá 24 giờ chuyển EXPIRED và xóa khỏi MinIO (US-20.3). */
@Component
public class ExportExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(ExportExpiryJob.class);

    private final ExportService exports;
    private final JdbcClient jdbc;

    public ExportExpiryJob(ExportService exports, JdbcClient jdbc) {
        this.exports = exports;
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelayString = "${camhub.export.expiry-interval:600000}", initialDelay = 90_000)
    public void run() {
        List<UUID> orgs = jdbc.sql("select camhub_organization_ids()").query(UUID.class).list();
        for (UUID org : orgs) {
            int n = OrganizationContext.callAs(org, exports::expireOrganization);
            if (n > 0) {
                log.info("Đã xử lý {} clip hết hạn/treo của Tổ chức {}", n, org);
            }
        }
    }

    /** Chạy ngay cho Tổ chức hiện tại (test). */
    public int runCurrentOrganization() {
        return exports.expireOrganization();
    }
}
