package it.ristomanager.cloud.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A connection as the database owner, which row-level security does not apply to. Tests use it to plant
 * data the application itself could never create (rows from 200 days ago) and to look at what the
 * restricted application role is not allowed to see. Never used by the application.
 */
public class OwnerDb implements AutoCloseable {

    private final Connection connection;

    public OwnerDb(PostgreSQLContainer postgres) {
        try {
            connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void run(String sql, Object... params) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bind(ps, params);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    public long count(String sql, Object... params) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    public <T> T one(String sql, Class<T> type, Object... params) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1, type);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    private static void bind(PreparedStatement ps, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // nothing to do
        }
    }
}
