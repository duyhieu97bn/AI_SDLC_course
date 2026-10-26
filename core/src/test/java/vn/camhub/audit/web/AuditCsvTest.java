package vn.camhub.audit.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Xuất nhật ký CSV (US-33): chặn CSV injection kể cả giá trị nhiều dòng, escape dấu nháy kép. */
class AuditCsvTest {

    @Test
    void oBatDauBangKyTuCongThucDuocVoHieu() {
        assertThat(AuditController.csv("=1+1")).isEqualTo("\"'=1+1\"");
        assertThat(AuditController.csv("=HYPERLINK(\"http://x\")\nCổng")).startsWith("\"'=HYPERLINK(");
        assertThat(AuditController.csv("\tcmd")).isEqualTo("\"'\tcmd\"");
        assertThat(AuditController.csv("@SUM(A1)")).startsWith("\"'@");
        assertThat(AuditController.csv("-2+3")).startsWith("\"'-");
    }

    @Test
    void giaTriThuongGiuNguyen() {
        assertThat(AuditController.csv("Cổng chính 1")).isEqualTo("\"Cổng chính 1\"");
        assertThat(AuditController.csv("a\"b")).isEqualTo("\"a\"\"b\"");
        assertThat(AuditController.csv("")).isEqualTo("\"\"");
        assertThat(AuditController.csv(null)).isEmpty();
    }
}
