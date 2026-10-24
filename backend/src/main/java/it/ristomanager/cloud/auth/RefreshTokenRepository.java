package it.ristomanager.cloud.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class RefreshTokenRepository {

    record Stored(UUID id, UUID userId, Instant expiresAt, boolean revoked) {
    }

    private final JdbcClient jdbc;

    RefreshTokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(UUID userId, String tokenHash, Instant expiresAt) {
        jdbc.sql("insert into refresh_token (user_id, token_hash, expires_at) values (:u, :h, :e)")
                .param("u", userId)
                .param("h", tokenHash)
                .param("e", java.sql.Timestamp.from(expiresAt))
                .update();
    }

    Optional<Stored> findByHash(String tokenHash) {
        return jdbc.sql("select id, user_id, expires_at, revoked from refresh_token where token_hash = :h")
                .param("h", tokenHash)
                .query((rs, n) -> new Stored(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
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
