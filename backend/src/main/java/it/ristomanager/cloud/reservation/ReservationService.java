package it.ristomanager.cloud.reservation;

import it.ristomanager.cloud.common.ApiException;
import it.ristomanager.cloud.floor.FloorRepository;
import it.ristomanager.cloud.floor.FloorTypes.DiningTable;
import it.ristomanager.cloud.floor.FloorTypes.ServiceStatus;
import it.ristomanager.cloud.reservation.ReservationTypes.ReservationRequest;
import it.ristomanager.cloud.reservation.ReservationTypes.Reservation;
import it.ristomanager.cloud.restaurant.RestaurantRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationService {

    private static final DateTimeFormatter HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm");

    private final ReservationRepository reservations;
    private final FloorRepository floor;
    private final RestaurantRepository restaurants;

    ReservationService(ReservationRepository reservations, FloorRepository floor, RestaurantRepository restaurants) {
        this.reservations = reservations;
        this.floor = floor;
        this.restaurants = restaurants;
    }

    @Transactional
    public Reservation create(UUID rid, UUID userId, ReservationRequest draft) {
        ZoneId zone = restaurants.timezone(rid);
        ReservationRules.validate(draft, zone, Instant.now(), true);
        Reservation candidate = toReservation(null, draft);
        checkTable(rid, candidate, null, zone);
        try {
            return reservations.insert(rid, userId, candidate);
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    /**
     * Runs the same rules {@link #create} applies to a new reservation (name, day not in the past, party size, phone...)
     * without writing anything (it only reads the restaurant's time zone). Lets a caller refuse a bad request early. It does not check the table: that check
     * needs the table lock and only {@link #create} may decide it.
     */
    public void validateDraft(UUID rid, ReservationRequest draft) {
        ReservationRules.validate(draft, restaurants.timezone(rid), Instant.now(), true);
    }

    @Transactional
    public Reservation update(UUID rid, UUID id, ReservationRequest draft) {
        reservations.find(rid, id).orElseThrow(() -> ApiException.notFound("No such reservation"));
        ZoneId zone = restaurants.timezone(rid);
        ReservationRules.validate(draft, zone, Instant.now(), false);
        Reservation candidate = toReservation(id, draft);
        checkTable(rid, candidate, id, zone);
        try {
            return reservations.update(rid, id, candidate)
                    .orElseThrow(() -> ApiException.notFound("No such reservation"));
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    /** Changes only the status. Reviving a cancelled booking re-checks that its table is still free. */
    @Transactional
    public Reservation changeStatus(UUID rid, UUID id, ReservationStatus status) {
        Reservation existing = reservations.find(rid, id).orElseThrow(() -> ApiException.notFound("No such reservation"));
        return update(rid, id, existing.toRequest(status));
    }

    @Transactional
    public void delete(UUID rid, UUID id) {
        if (!reservations.delete(rid, id)) {
            throw ApiException.notFound("No such reservation");
        }
    }

    public Reservation get(UUID rid, UUID id) {
        return reservations.find(rid, id).orElseThrow(() -> ApiException.notFound("No such reservation"));
    }

    /** Reservations that start on the given day, in the restaurant's own time zone. */
    public List<Reservation> listForDay(UUID rid, LocalDate day) {
        ZoneId zone = restaurants.timezone(rid);
        Instant from = day.atStartOfDay(zone).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(zone).toInstant();
        return reservations.listBetween(rid, from, to);
    }

    /**
     * Tables that could take this party at this time, smallest first: seating two
     * guests at a table for ten is a waste the system should discourage.
     */
    public List<DiningTable> availableTables(UUID rid, OffsetDateTime startsAt, Integer durationMinutes,
                                             int partySize, UUID excludeReservationId) {
        int duration = durationMinutes == null ? ReservationRules.DEFAULT_DURATION_MINUTES : durationMinutes;
        Instant start = startsAt.toInstant();
        Instant end = start.plusSeconds(duration * 60L);
        Set<UUID> busy = Set.copyOf(reservations.busyTables(rid, start, end, excludeReservationId));
        return floor.listAllTables(rid).stream()
                .filter(t -> t.serviceStatus() != ServiceStatus.OUT_OF_SERVICE)
                .filter(t -> t.seats() >= partySize)
                .filter(t -> !busy.contains(t.id()))
                .sorted(Comparator.comparingInt(DiningTable::seats).thenComparingInt(DiningTable::number))
                .toList();
    }

    private static Reservation toReservation(UUID id, ReservationRequest d) {
        return new Reservation(id, d.tableId(), d.guestName(), d.phone(), d.email(),
                d.startsAt().toInstant(), ReservationRules.durationOf(d), d.partySize(),
                d.highChairs() == null ? 0 : d.highChairs(),
                d.strollerSpaces() == null ? 0 : d.strollerSpaces(),
                ReservationRules.statusOf(d), d.allergens() == null ? List.of() : d.allergens(), d.notes());
    }

    /** Table rules apply only while the booking holds the table; closing or cancelling must always work. */
    private void checkTable(UUID rid, Reservation r, UUID excludeId, ZoneId zone) {
        if (r.tableId() == null || !r.status().occupiesTable()) {
            return;
        }
        DiningTable table = floor.findTable(rid, r.tableId())
                .orElseThrow(() -> ApiException.invalid("tableId", "The assigned table does not exist"));
        reservations.lockTable(table.id());
        if (table.serviceStatus() == ServiceStatus.OUT_OF_SERVICE) {
            throw ApiException.invalid("tableId", "Table " + table.number() + " is out of service");
        }
        if (table.seats() < r.partySize()) {
            throw ApiException.invalid("tableId", "Table " + table.number() + " has " + table.seats()
                    + " seats, not enough for " + r.partySize() + " guests");
        }
        reservations.findConflict(rid, r.tableId(), r.startsAt(), r.endsAt(), excludeId).ifPresent(c -> {
            throw ApiException.conflict("Table " + table.number() + " is already booked by " + c.guestName()
                    + " at " + HOUR_MINUTE.format(c.startsAt().atZone(zone)));
        });
    }

    /**
     * The friendly check above runs first, but two people can book the same table
     * in the same instant. The database constraint is the real guarantee; this
     * turns its refusal into the same readable conflict.
     */
    private static RuntimeException translate(DataIntegrityViolationException e) {
        String message = String.valueOf(e.getMostSpecificCause().getMessage());
        if (message.contains("reservation_no_double_booking")) {
            return ApiException.conflict("That table was just booked by someone else at an overlapping time");
        }
        return e;
    }
}
