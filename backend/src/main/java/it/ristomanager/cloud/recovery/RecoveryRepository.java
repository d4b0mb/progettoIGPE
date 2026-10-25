package it.ristomanager.cloud.recovery;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class RecoveryRepository {

    record Stored(UUID userId, UUID restaurantId, String question1, String answer1Hash, String question2, String answer2Hash) {
    }

    record Questions(String question1, String question2, Instant updatedAt) {
    }

    private final JdbcClient jdbc;

    RecoveryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Before sign-in there is no restaurant yet, so this uses the narrow lookup from V8. */
    Optional<Stored> findByEmail(String email) {
        return jdbc.sql("select user_id, restaurant_id, question_1, answer_1_hash, question_2, answer_2_hash from auth_find_recovery(:email)")
                .param("email", email)
                .query((rs, n) -> new Stored(rs.getObject("user_id", UUID.class), rs.getObject("restaurant_id", UUID.class),
                        rs.getString("question_1"), rs.getString("answer_1_hash"), rs.getString("question_2"), rs.getString("answer_2_hash")))
                .optional();
    }

    Optional<Questions> questionsOf(UUID restaurantId, UUID userId) {
        return jdbc.sql("select question_1, question_2, updated_at from owner_recovery where restaurant_id = :rid and user_id = :uid")
                .param("rid", restaurantId).param("uid", userId)
                .query((rs, n) -> new Questions(rs.getString("question_1"), rs.getString("question_2"),
                        rs.getObject("updated_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    void save(UUID restaurantId, UUID userId, String q1, String a1Hash, String q2, String a2Hash) {
        jdbc.sql("""
                insert into owner_recovery (user_id, restaurant_id, question_1, answer_1_hash, question_2, answer_2_hash)
                values (:uid, :rid, :q1, :a1, :q2, :a2)
                on conflict (user_id) do update set question_1 = :q1, answer_1_hash = :a1,
                    question_2 = :q2, answer_2_hash = :a2, updated_at = now()
                """)
                .param("uid", userId).param("rid", restaurantId).param("q1", q1).param("a1", a1Hash)
                .param("q2", q2).param("a2", a2Hash).update();
    }
}
