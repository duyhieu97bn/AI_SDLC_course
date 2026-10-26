package vn.camhub.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import vn.camhub.IntegrationTestSupport;
import vn.camhub.platform.crypto.CryptoService;
import vn.camhub.platform.tenancy.OrganizationContext;

/** Tiêu chí TS-04 "Cách ly dữ liệu theo Tổ chức" (INV-01). */
class TenancyIT extends IntegrationTestSupport {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    CryptoService crypto;

    UUID orgA;
    UUID orgB;

    @BeforeEach
    void twoOrganizationsWithData() {
        orgA = createOrg("A");
        orgB = createOrg("B");
        OrganizationContext.runAs(orgA, () -> insertProbe(orgA, "dữ liệu A"));
        OrganizationContext.runAs(orgB, () -> insertProbe(orgB, "dữ liệu B"));
    }

    @Test
    void queryWithoutOrganizationFilterOnlySeesOwnRows() {
        // Truy vấn "quên" điều kiện organization_id vẫn chỉ thấy dữ liệu của Tổ chức hiện tại
        List<String> seenByA = OrganizationContext.callAs(orgA,
                () -> jdbc.queryForList("select label from rls_probe", String.class));
        assertThat(seenByA).containsExactly("dữ liệu A");

        List<UUID> orgsSeenByB = OrganizationContext.callAs(orgB,
                () -> jdbc.queryForList("select id from organization", UUID.class));
        assertThat(orgsSeenByB).containsExactly(orgB);
    }

    @Test
    void noOrganizationContextSeesNothing() {
        assertThat(jdbc.queryForObject("select count(*) from rls_probe", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from organization", Long.class)).isZero();
    }

    @Test
    void cannotWriteIntoAnotherOrganization() {
        assertThatThrownBy(() -> OrganizationContext.runAs(orgA, () -> insertProbe(orgB, "chèn trộm")))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("row-level security");
        int updated = OrganizationContext.callAs(orgA,
                () -> jdbc.update("update rls_probe set label = 'sửa trộm' where organization_id = ?", orgB));
        assertThat(updated).isZero();
    }

    @Test
    void pooledConnectionDoesNotLeakOrganization() {
        // Một giao dịch của A trả kết nối về pool; lần lấy kết nối sau không có Tổ chức phải thấy 0 dòng
        for (int i = 0; i < 30; i++) {
            OrganizationContext.runAs(orgA, () -> tx.executeWithoutResult(s ->
                    jdbc.queryForObject("select count(*) from rls_probe", Long.class)));
            assertThat(jdbc.queryForObject("select count(*) from rls_probe", Long.class)).isZero();
        }
    }

    @Test
    void dataKeyIsStoredPerOrganizationUnderRls() {
        String ct = crypto.encrypt(orgA, "camera-pass");
        assertThat(crypto.decrypt(orgA, ct)).isEqualTo("camera-pass");
        String wrapped = OrganizationContext.callAs(orgA,
                () -> jdbc.queryForObject("select data_key_enc from organization where id = ?", String.class, orgA));
        assertThat(wrapped).startsWith("mk1:");
    }

    private UUID createOrg(String name) {
        UUID id = UUID.randomUUID();
        OrganizationContext.runAs(id, () -> jdbc.update("insert into organization (id, code, name) values (?, ?, ?)",
                id, "org-" + id, "Tổ chức " + name));
        return id;
    }

    private void insertProbe(UUID org, String label) {
        jdbc.update("insert into rls_probe (organization_id, label) values (?, ?)", org, label);
    }
}
