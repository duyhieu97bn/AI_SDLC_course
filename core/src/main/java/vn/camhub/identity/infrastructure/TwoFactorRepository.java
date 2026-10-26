package vn.camhub.identity.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.camhub.platform.tenancy.OrganizationContext;

@Repository
public class TwoFactorRepository {

    private final JdbcClient jdbc;

    public TwoFactorRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> secretOf(UUID userId) {
        return jdbc.sql("select totp_secret_enc from app_user where id = ? and two_factor_enabled").param(userId)
                .query(String.class).optional();
    }

    public void enable(UUID userId, String secretEnc) {
        jdbc.sql("update app_user set totp_secret_enc = ?, two_factor_enabled = true, updated_at = now() where id = ?")
                .params(secretEnc, userId).update();
    }

    public void disable(UUID userId) {
        jdbc.sql("update app_user set totp_secret_enc = null, two_factor_enabled = false, updated_at = now() where id = ?")
                .param(userId).update();
        jdbc.sql("delete from recovery_code where user_id = ?").param(userId).update();
    }

    public void replaceRecoveryCodes(UUID userId, List<String> hashes) {
        jdbc.sql("delete from recovery_code where user_id = ?").param(userId).update();
        for (String h : hashes) {
            jdbc.sql("insert into recovery_code (organization_id, user_id, code_hash) values (?, ?, ?)")
                    .params(OrganizationContext.require(), userId, h).update();
        }
    }

    /** Đánh dấu đã dùng nếu mã còn hiệu lực; trả về true khi dùng thành công (mỗi mã dùng đúng một lần). */
    public boolean useRecoveryCode(UUID userId, String hash) {
        return jdbc.sql("""
                update recovery_code set used_at = now()
                where id = (select id from recovery_code where user_id = ? and code_hash = ? and used_at is null
                            limit 1 for update)
                """).params(userId, hash).update() == 1;
    }

    public int remainingRecoveryCodes(UUID userId) {
        return jdbc.sql("select count(*) from recovery_code where user_id = ? and used_at is null").param(userId)
                .query(Integer.class).single();
    }
}
