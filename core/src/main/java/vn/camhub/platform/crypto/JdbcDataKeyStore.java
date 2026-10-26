package vn.camhub.platform.crypto;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import vn.camhub.platform.tenancy.OrganizationContext;

/** Lưu khóa dữ liệu trong {@code organization.data_key_enc}. Chạy trong ngữ cảnh Tổ chức vì bảng có RLS. */
@Component
public class JdbcDataKeyStore implements DataKeyStore {

    private final JdbcTemplate jdbc;

    public JdbcDataKeyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> find(UUID orgId) {
        List<String> rows = OrganizationContext.callAs(orgId, () -> jdbc
                .queryForList("select data_key_enc from organization where id = ?", String.class, orgId));
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Không tìm thấy Tổ chức " + orgId);
        }
        return Optional.ofNullable(rows.get(0));
    }

    @Override
    public String saveIfAbsent(UUID orgId, String wrappedKey) {
        return OrganizationContext.callAs(orgId, () -> {
            jdbc.update("update organization set data_key_enc = ? where id = ? and data_key_enc is null", wrappedKey, orgId);
            return jdbc.queryForObject("select data_key_enc from organization where id = ?", String.class, orgId);
        });
    }
}
