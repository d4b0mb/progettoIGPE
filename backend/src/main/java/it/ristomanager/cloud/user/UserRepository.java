package it.ristomanager.cloud.user;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

    private static final String COLUMNS = "id, restaurant_id, email, password_hash, display_name, role, active";

    private final JdbcClient jdbc;

    UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AppUser> findByEmail(String email) {
        return jdbc.sql("select " + COLUMNS + " from app_user where email = :email")
                .param("email", email)
                .query(UserRepository::map)
                .optional();
    }

    public Optional<AppUser> findById(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from app_user where id = :id")
                .param("id", id)
                .query(UserRepository::map)
                .optional();
    }

    public boolean emailExists(String email) {
        return jdbc.sql("select exists(select 1 from app_user where email = :email)")
                .param("email", email)
                .query(Boolean.class)
                .single();
    }

    public AppUser insert(UUID restaurantId, String email, String passwordHash, String displayName, Role role) {
        return jdbc.sql("""
                insert into app_user (restaurant_id, email, password_hash, display_name, role)
                values (:restaurantId, :email, :hash, :name, :role)
                returning """ + " " + COLUMNS)
                .param("restaurantId", restaurantId)
                .param("email", email)
                .param("hash", passwordHash)
                .param("name", displayName)
                .param("role", role.name())
                .query(UserRepository::map)
                .single();
    }

    public List<AppUser> listByRestaurant(UUID restaurantId) {
        return jdbc.sql("select " + COLUMNS + " from app_user where restaurant_id = :rid order by display_name")
                .param("rid", restaurantId)
                .query(UserRepository::map)
                .list();
    }

    /** @return true if a user of that restaurant was deactivated. */
    public boolean deactivate(UUID restaurantId, UUID userId) {
        return jdbc.sql("update app_user set active = false where id = :id and restaurant_id = :rid")
                .param("id", userId)
                .param("rid", restaurantId)
                .update() == 1;
    }

    private static AppUser map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new AppUser(
                rs.getObject("id", UUID.class),
                rs.getObject("restaurant_id", UUID.class),
                rs.getString("email"),
                rs.getString("password_hash"),
                rs.getString("display_name"),
                Role.valueOf(rs.getString("role")),
                rs.getBoolean("active"));
    }
}
