package it.unical.igpe.ristorante.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import it.unical.igpe.ristorante.model.Role;
import it.unical.igpe.ristorante.model.User;

/**
 * Accesso alla tabella users.
 *
 * Tutte le query usano PreparedStatement con parametri "?": le slide del corso
 * lo indicano come regola per evitare la SQL injection, e vale a maggior ragione
 * per la schermata di login, che è il punto più esposto dell'applicazione.
 */
public class UserDao {

    private final Connection connection;

    public UserDao(Database database) {
        this.connection = database.getConnection();
    }

    public List<User> findAll() {
        String sql = "SELECT * FROM users ORDER BY role, username;";
        List<User> result = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                result.add(mapRow(rs));
            }
        } catch (SQLException e) {
            throw new DataAccessException("Lettura degli utenti fallita", e);
        }
        return result;
    }

    public User findByUsername(String username) {
        String sql = "SELECT * FROM users WHERE username = ?;";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, username);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        } catch (SQLException e) {
            throw new DataAccessException("Ricerca dell'utente fallita", e);
        }
    }

    public int insert(User user) {
        String sql = "INSERT INTO users (username, password_hash, full_name, role, active, created_at) "
                   + "VALUES (?, ?, ?, ?, ?, ?);";
        try (PreparedStatement stmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, user.getUsername());
            stmt.setString(2, user.getPasswordHash());
            stmt.setString(3, user.getFullName());
            stmt.setString(4, user.getRole().name());
            stmt.setInt(5, user.isActive() ? 1 : 0);
            stmt.setString(6, Converters.toText(user.getCreatedAt()));
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                if (keys.next()) {
                    user.setId(keys.getInt(1));
                }
            }
            return user.getId();
        } catch (SQLException e) {
            throw new DataAccessException("Inserimento dell'utente fallito", e);
        }
    }

    public void update(User user) {
        String sql = "UPDATE users SET username = ?, password_hash = ?, full_name = ?, "
                   + "role = ?, active = ?, last_login_at = ? WHERE id = ?;";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, user.getUsername());
            stmt.setString(2, user.getPasswordHash());
            stmt.setString(3, user.getFullName());
            stmt.setString(4, user.getRole().name());
            stmt.setInt(5, user.isActive() ? 1 : 0);
            stmt.setString(6, Converters.toText(user.getLastLoginAt()));
            stmt.setInt(7, user.getId());
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException("Aggiornamento dell'utente fallito", e);
        }
    }

    public void delete(int userId) {
        try (PreparedStatement stmt = connection.prepareStatement("DELETE FROM users WHERE id = ?;")) {
            stmt.setInt(1, userId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException("Eliminazione dell'utente fallita", e);
        }
    }

    public int count() {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT COUNT(*) FROM users;");
             ResultSet rs = stmt.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new DataAccessException("Conteggio degli utenti fallito", e);
        }
    }

    /** Traduce una riga del ResultSet in un oggetto del modello. */
    private User mapRow(ResultSet rs) throws SQLException {
        User user = new User();
        user.setId(rs.getInt("id"));
        user.setUsername(rs.getString("username"));
        user.setPasswordHash(rs.getString("password_hash"));
        user.setFullName(rs.getString("full_name"));
        user.setRole(Converters.toEnum(Role.class, rs.getString("role"), Role.VIEWER));
        user.setActive(rs.getInt("active") == 1);
        LocalDateTime created = Converters.toDateTime(rs.getString("created_at"));
        user.setCreatedAt(created == null ? LocalDateTime.now() : created);
        user.setLastLoginAt(Converters.toDateTime(rs.getString("last_login_at")));
        return user;
    }
}
