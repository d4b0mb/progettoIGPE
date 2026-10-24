package it.ristomanager.cloud.tenant;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Tells PostgreSQL which restaurant each connection is working for.
 *
 * On every checkout from the pool it sets the session variable
 * {@code app.restaurant_id} (empty when no restaurant is in context). Because the
 * value is overwritten on every checkout, a connection can never carry a previous
 * request's restaurant into the next one.
 */
class TenantDataSource extends DelegatingDataSource {

    TenantDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return withTenant(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return withTenant(super.getConnection(username, password));
    }

    private Connection withTenant(Connection connection) throws SQLException {
        String restaurantId = TenantContext.current().map(UUID::toString).orElse("");
        try (PreparedStatement ps = connection.prepareStatement("select set_config('app.restaurant_id', ?, false)")) {
            ps.setString(1, restaurantId);
            ps.execute();
        } catch (SQLException | RuntimeException e) {
            connection.close();
            throw e;
        }
        return connection;
    }
}
