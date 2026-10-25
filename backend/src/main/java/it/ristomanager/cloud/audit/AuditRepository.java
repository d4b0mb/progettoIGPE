package it.ristomanager.cloud.audit;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
class AuditRepository {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, String>> STRINGS = new TypeReference<>() {
    };

    private final JdbcClient jdbc;

    AuditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Newest first. {@code before} continues from where the previous page ended. */
    List<AuditEntry> find(UUID restaurantId, Instant from, Instant to, Instant before, UUID actorId, int limit) {
        return jdbc.sql("""
                select a.id, a.occurred_at, a.actor_user_id,
                       coalesce(nullif(u.display_name, ''), nullif(a.actor_label, ''), '') as actor_name,
                       a.action, a.target_type, a.target_id, a.outcome, a.details::text as details, a.ip
                  from audit_event a left join app_user u on u.id = a.actor_user_id
                 where a.restaurant_id = :rid
                   and a.occurred_at >= :from and a.occurred_at < :to
                   and (cast(:before as timestamptz) is null or a.occurred_at < :before)
                   and (cast(:actor as uuid) is null or a.actor_user_id = :actor)
                 order by a.occurred_at desc, a.id desc
                 limit :limit
                """)
                .param("rid", restaurantId).param("from", utc(from)).param("to", utc(to))
                .param("before", before == null ? null : utc(before)).param("actor", actorId).param("limit", limit)
                .query(AuditRepository::map).list();
    }

    private static OffsetDateTime utc(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    private static AuditEntry map(ResultSet rs, int n) throws SQLException {
        return new AuditEntry(rs.getObject("id", UUID.class), rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                rs.getObject("actor_user_id", UUID.class), rs.getString("actor_name"), rs.getString("action"),
                rs.getString("target_type"), rs.getString("target_id"), rs.getString("outcome"),
                JSON.readValue(rs.getString("details"), STRINGS), rs.getString("ip"));
    }
}
