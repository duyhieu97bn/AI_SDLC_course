package vn.camhub.platform.tenancy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Bọc DataSource của ứng dụng: mỗi lần lấy kết nối từ pool thì đặt {@code app.org_id}
 * theo {@link OrganizationContext}. Giao dịch Spring giữ một kết nối suốt giao dịch,
 * nên giá trị không đổi trong giao dịch.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    static final String SET_ORG = "select set_config('app.org_id', ?, false)";

    public TenantAwareDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return applyOrganization(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return applyOrganization(super.getConnection(username, password));
    }

    private Connection applyOrganization(Connection connection) throws SQLException {
        String orgId = OrganizationContext.current().map(UUID::toString).orElse("");
        try (PreparedStatement ps = connection.prepareStatement(SET_ORG)) {
            ps.setString(1, orgId);
            ps.execute();
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return connection;
    }
}
