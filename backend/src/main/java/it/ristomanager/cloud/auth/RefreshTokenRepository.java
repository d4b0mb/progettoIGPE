package it.ristomanager.cloud.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class RefreshTokenRepository {

    record Stored(UUID id, UUID userId, UUID restaurantId, Instant expiresAt, boolean revoked) {
    }

    private final JdbcClient jdbc;

    RefreshTokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(UUID userId, UUID restaurantId, String tokenHash, Instant expiresAt) {
        jdbc.sql("insert into refresh_token (user_id, restaurant_id, token_hash, expires_at) values (:u, :r, :h, :e)")
                .param("u", userId)
                .param("r", restaurantId)
                .param("h", tokenHash)
                .param("e", java.sql.Timestamp.from(expiresAt))
                .update();
    }

    /** Cross-tenant by necessity: the caller has no restaurant yet. Uses the narrow function from V5. */
    Optional<Stored> findByHash(String tokenHash) {
        return jdbc.sql("select id, user_id, restaurant_id, expires_at, revoked from auth_find_refresh_token(:h)")
                .param("h", tokenHash)
                .query((rs, n) -> new Stored(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("restaurant_id", UUID.class),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getBoolean("revoked")))
                .optional();
    }

    void revoke(UUID id) {
        jdbc.sql("update refresh_token set revoked = true where id = :id").param("id", id).update();
    }

    void revokeAllForUser(UUID userId) {
        jdbc.sql("update refresh_token set revoked = true where user_id = :u and not revoked")
                .param("u", userId)
                .update();
    }
}
