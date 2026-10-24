package it.ristomanager.cloud.reservation;

import it.ristomanager.cloud.common.Allergen;
import it.ristomanager.cloud.floor.FloorTypes.LiveStatus;
import it.ristomanager.cloud.reservation.ReservationTypes.Reservation;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ReservationRepository {

    private static final String COLS = "id, table_id, guest_name, phone, email, starts_at, "
            + "(extract(epoch from (ends_at - starts_at)) / 60)::int as duration_minutes, "
            + "party_size, high_chairs, stroller_spaces, status, allergens, notes";

    private static final String LIVE = "status in ('PENDING', 'CONFIRMED', 'ARRIVED')";

    private final JdbcClient jdbc;

    ReservationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static OffsetDateTime utc(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    private static String[] allergenNames(List<Allergen> allergens) {
        return allergens == null ? new String[0]
                : allergens.stream().map(Allergen::name).distinct().sorted().toArray(String[]::new);
    }

    public Reservation insert(UUID rid, UUID createdBy, Reservation r) {
        return jdbc.sql("""
                insert into reservation (restaurant_id, table_id, guest_name, phone, email, starts_at, ends_at,
                    party_size, high_chairs, stroller_spaces, status, allergens, notes, created_by)
                values (:rid, :table, :guest, :phone, :email, :starts, :ends,
                    :party, :chairs, :strollers, :status, cast(:allergens as text[]), :notes, :by)
                returning\s""" + COLS)
                .param("rid", rid).param("table", r.tableId()).param("guest", r.guestName().trim())
                .param("phone", r.phone().trim()).param("email", blankToNull(r.email()))
                .param("starts", utc(r.startsAt())).param("ends", utc(r.endsAt()))
                .param("party", r.partySize()).param("chairs", r.highChairs()).param("strollers", r.strollerSpaces())
                .param("status", r.status().name()).param("allergens", allergenNames(r.allergens()))
                .param("notes", r.notes() == null ? "" : r.notes()).param("by", createdBy)
                .query(ReservationRepository::map).single();
    }

    public Optional<Reservation> update(UUID rid, UUID id, Reservation r) {
        return jdbc.sql("""
                update reservation set table_id = :table, guest_name = :guest, phone = :phone, email = :email,
                    starts_at = :starts, ends_at = :ends, party_size = :party, high_chairs = :chairs,
                    stroller_spaces = :strollers, status = :status, allergens = cast(:allergens as text[]),
                    notes = :notes, updated_at = now()
                where restaurant_id = :rid and id = :id
                returning\s""" + COLS)
                .param("rid", rid).param("id", id).param("table", r.tableId()).param("guest", r.guestName().trim())
                .param("phone", r.phone().trim()).param("email", blankToNull(r.email()))
                .param("starts", utc(r.startsAt())).param("ends", utc(r.endsAt()))
                .param("party", r.partySize()).param("chairs", r.highChairs()).param("strollers", r.strollerSpaces())
                .param("status", r.status().name()).param("allergens", allergenNames(r.allergens()))
                .param("notes", r.notes() == null ? "" : r.notes())
                .query(ReservationRepository::map).optional();
    }

    public Optional<Reservation> find(UUID rid, UUID id) {
        return jdbc.sql("select " + COLS + " from reservation where restaurant_id = :rid and id = :id")
                .param("rid", rid).param("id", id).query(ReservationRepository::map).optional();
    }

    public boolean delete(UUID rid, UUID id) {
        return jdbc.sql("delete from reservation where restaurant_id = :rid and id = :id")
                .param("rid", rid).param("id", id).update() == 1;
    }

    public List<Reservation> listBetween(UUID rid, Instant from, Instant to) {
        return jdbc.sql("select " + COLS + " from reservation where restaurant_id = :rid "
                        + "and starts_at >= :from and starts_at < :to order by starts_at, guest_name")
                .param("rid", rid).param("from", utc(from)).param("to", utc(to))
                .query(ReservationRepository::map).list();
    }

    /**
     * Makes bookings of the same table wait their turn until the transaction ends.
     * Without it, many simultaneous bookings of one table can deadlock inside the
     * database's overlap constraint. With it, the second request sees the first
     * one's committed booking and gets a clear conflict. The constraint stays as the
     * final guarantee.
     */
    public void lockTable(UUID tableId) {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(cast(:t as text), 0))")
                .param("t", tableId).query().singleRow();
    }

    /** The first live reservation on this table that overlaps the given time range, if any. */
    public Optional<Reservation> findConflict(UUID rid, UUID tableId, Instant start, Instant end, UUID excludeId) {
        return jdbc.sql("select " + COLS + " from reservation where restaurant_id = :rid and table_id = :table "
                        + "and " + LIVE + " and tstzrange(starts_at, ends_at) && tstzrange(:s, :e) "
                        + "and (cast(:exclude as uuid) is null or id <> :exclude) order by starts_at limit 1")
                .param("rid", rid).param("table", tableId).param("s", utc(start)).param("e", utc(end))
                .param("exclude", excludeId)
                .query(ReservationRepository::map).optional();
    }

    /** Ids of the tables that already hold a live reservation overlapping the given range. */
    public List<UUID> busyTables(UUID rid, Instant start, Instant end, UUID excludeId) {
        return jdbc.sql("select distinct table_id from reservation where restaurant_id = :rid and table_id is not null "
                        + "and " + LIVE + " and tstzrange(starts_at, ends_at) && tstzrange(:s, :e) "
                        + "and (cast(:exclude as uuid) is null or id <> :exclude)")
                .param("rid", rid).param("s", utc(start)).param("e", utc(end)).param("exclude", excludeId)
                .query(UUID.class).list();
    }

    /**
     * What each table looks like at {@code now}: occupied if a guest has arrived,
     * reserved if a booking starts within the next hour.
     */
    public Map<UUID, LiveStatus> liveStatuses(UUID rid, Instant now) {
        Map<UUID, LiveStatus> result = new HashMap<>();
        jdbc.sql("select table_id, status from reservation where restaurant_id = :rid and table_id is not null "
                        + "and " + LIVE + " and starts_at < :horizon and ends_at > :now")
                .param("rid", rid).param("now", utc(now)).param("horizon", utc(now.plusSeconds(3600)))
                .query((rs, n) -> Map.entry(rs.getObject("table_id", UUID.class), rs.getString("status")))
                .list()
                .forEach(e -> {
                    LiveStatus status = "ARRIVED".equals(e.getValue()) ? LiveStatus.OCCUPIED : LiveStatus.RESERVED;
                    result.merge(e.getKey(), status, (a, b) -> a == LiveStatus.OCCUPIED ? a : b);
                });
        return result;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static Reservation map(ResultSet rs, int n) throws SQLException {
        String[] allergens = (String[]) rs.getArray("allergens").getArray();
        return new Reservation(
                rs.getObject("id", UUID.class),
                rs.getObject("table_id", UUID.class),
                rs.getString("guest_name"),
                rs.getString("phone"),
                rs.getString("email"),
                rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
                rs.getInt("duration_minutes"),
                rs.getInt("party_size"),
                rs.getInt("high_chairs"),
                rs.getInt("stroller_spaces"),
                ReservationStatus.valueOf(rs.getString("status")),
                Arrays.stream(allergens).map(Allergen::valueOf).toList(),
                rs.getString("notes"));
    }
}
