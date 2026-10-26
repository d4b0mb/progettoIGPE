package it.ristomanager.cloud.assistant;

import it.ristomanager.cloud.common.Allergen;
import it.ristomanager.cloud.floor.FloorRepository;
import it.ristomanager.cloud.floor.FloorTypes.DiningTable;
import it.ristomanager.cloud.floor.FloorTypes.LiveStatus;
import it.ristomanager.cloud.menu.MenuRepository;
import it.ristomanager.cloud.order.OrderRepository;
import it.ristomanager.cloud.reservation.ReservationRepository;
import it.ristomanager.cloud.reservation.ReservationService;
import it.ristomanager.cloud.reservation.ReservationStatus;
import it.ristomanager.cloud.reservation.ReservationTypes.Reservation;
import it.ristomanager.cloud.reservation.ReservationTypes.ReservationRequest;
import it.ristomanager.cloud.restaurant.RestaurantRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The real data behind the assistant's tools, taken from the same services and repositories the screens use, so the
 * assistant sees exactly what the signed-in person's role would see there and creates and cancels through the same
 * rules (table conflicts, past days, the database's no-double-booking guarantee).
 */
@Component
class SpringAssistantData implements AssistantData {

    static final int MAX_RESERVATIONS = 15;
    static final int MAX_TICKETS = 20;
    static final int MAX_MENU_RESULTS = 8;
    static final int NOTES_CUT = 160;
    private static final int LOOKAHEAD_DAYS = 60;
    private static final int CANCEL_LOOKAHEAD_DAYS = 14;
    private static final DateTimeFormatter HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm");
    private static final Set<ReservationStatus> CANCELLABLE = Set.of(ReservationStatus.PENDING, ReservationStatus.CONFIRMED);

    private final ReservationService reservationService;
    private final ReservationRepository reservationRepository;
    private final FloorRepository floor;
    private final MenuRepository menu;
    private final OrderRepository orders;
    private final RestaurantRepository restaurants;

    SpringAssistantData(ReservationService reservationService, ReservationRepository reservationRepository,
                        FloorRepository floor, MenuRepository menu, OrderRepository orders,
                        RestaurantRepository restaurants) {
        this.reservationService = reservationService;
        this.reservationRepository = reservationRepository;
        this.floor = floor;
        this.menu = menu;
        this.orders = orders;
        this.restaurants = restaurants;
    }

    @Override
    public ZoneId timezone(UUID restaurantId) {
        return restaurants.timezone(restaurantId);
    }

    @Override
    public List<ReservationRow> findReservations(UUID restaurantId, LocalDate dateOrNull, String nameOrNull) {
        return query(restaurantId, dateOrNull, nameOrNull, null, LOOKAHEAD_DAYS);
    }

    @Override
    public List<ReservationRow> findCancellable(UUID restaurantId, LocalDate dateOrNull, String name) {
        return query(restaurantId, dateOrNull, name, CANCELLABLE, CANCEL_LOOKAHEAD_DAYS);
    }

    private List<ReservationRow> query(UUID rid, LocalDate date, String name, Set<ReservationStatus> statuses, int lookaheadDays) {
        ZoneId zone = restaurants.timezone(rid);
        LocalDate first = date != null ? date : LocalDate.now(zone);
        LocalDate lastExclusive = date != null ? date.plusDays(1) : first.plusDays(lookaheadDays);
        List<Reservation> found = reservationRepository
                .listBetween(rid, first.atStartOfDay(zone).toInstant(), lastExclusive.atStartOfDay(zone).toInstant())
                .stream()
                .filter(r -> statuses == null || statuses.contains(r.status()))
                .filter(r -> name == null || name.isBlank() || TextFold.containsFolded(r.guestName(), name))
                .limit(MAX_RESERVATIONS)
                .toList();
        if (found.isEmpty()) {
            return List.of();
        }
        Map<UUID, Integer> numbers = tableNumbers(rid);
        return found.stream().map(r -> {
            var local = r.startsAt().atZone(zone);
            return new ReservationRow(r.id(), r.guestName(), r.partySize(), local.toLocalDate(),
                    HOUR_MINUTE.format(local), r.status().name(),
                    r.tableId() == null ? null : numbers.get(r.tableId()),
                    r.allergens().stream().map(Allergen::name).toList(),
                    TextFold.cut(r.notes(), NOTES_CUT));
        }).toList();
    }

    @Override
    public List<TableRow> floorStatus(UUID restaurantId) {
        Map<UUID, LiveStatus> live = reservationRepository.liveStatuses(restaurantId, Instant.now());
        return floor.listAllTables(restaurantId).stream()
                .map(t -> new TableRow(t.number(), t.seats(), liveStatusOf(t, live).name()))
                .toList();
    }

    /** The same rule the floor screen uses (FloorController): staff decisions first, then what reservations say. */
    private static LiveStatus liveStatusOf(DiningTable t, Map<UUID, LiveStatus> live) {
        return switch (t.serviceStatus()) {
            case OUT_OF_SERVICE -> LiveStatus.OUT_OF_SERVICE;
            case TO_CLEAN -> LiveStatus.TO_CLEAN;
            case FREE -> live.getOrDefault(t.id(), LiveStatus.FREE);
        };
    }

    @Override
    public List<TicketRow> kitchenQueue(UUID restaurantId) {
        return orders.listActive(restaurantId).stream()
                .limit(MAX_TICKETS)
                .map(t -> new TicketRow(t.code(), t.tableNumber(), t.status().name(),
                        t.items().stream().map(i -> new TicketLine(i.quantity(), i.name())).toList(),
                        t.hasAllergyConflicts()))
                .toList();
    }

    @Override
    public List<MenuRow> searchMenu(UUID restaurantId, String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return menu.list(restaurantId).stream()
                .filter(m -> TextFold.containsFolded(m.name(), query))
                .limit(MAX_MENU_RESULTS)
                .map(m -> new MenuRow(m.name(), m.priceCents(), m.course().name(),
                        m.allergens().stream().map(Allergen::name).toList(),
                        TextFold.cut(m.description(), 300)))
                .toList();
    }

    @Override
    public Optional<TableChoice> availableTable(UUID restaurantId, Instant startsAt, int partySize) {
        // Smallest first, so the first one is the tightest fit.
        return reservationService.availableTables(restaurantId, startsAt.atOffset(ZoneOffset.UTC), null, partySize, null)
                .stream().findFirst().map(t -> new TableChoice(t.id(), t.number(), t.seats()));
    }

    @Override
    public void validateDraft(UUID restaurantId, ReservationDraft draft) {
        reservationService.validateDraft(restaurantId, toRequest(draft));
    }

    @Override
    public BookingResult createReservation(UUID restaurantId, UUID userId, ReservationDraft draft) {
        Reservation created = reservationService.create(restaurantId, userId, toRequest(draft));
        return result(restaurantId, created);
    }

    @Override
    public BookingResult cancelReservation(UUID restaurantId, UUID reservationId) {
        Reservation cancelled = reservationService.changeStatus(restaurantId, reservationId, ReservationStatus.CANCELLED);
        return result(restaurantId, cancelled);
    }

    private static ReservationRequest toRequest(ReservationDraft d) {
        return new ReservationRequest(d.guestName(), d.phone(), null, d.startsAt().atOffset(ZoneOffset.UTC), null,
                d.partySize(), null, null, d.tableId(), ReservationStatus.CONFIRMED, d.allergens(), d.notes());
    }

    private BookingResult result(UUID rid, Reservation r) {
        var local = r.startsAt().atZone(restaurants.timezone(rid));
        Integer tableNumber = r.tableId() == null ? null
                : floor.findTable(rid, r.tableId()).map(DiningTable::number).orElse(null);
        return new BookingResult(r.id(), r.guestName(), r.partySize(), local.toLocalDate().toString(),
                HOUR_MINUTE.format(local), tableNumber);
    }

    private Map<UUID, Integer> tableNumbers(UUID rid) {
        Map<UUID, Integer> numbers = new HashMap<>();
        floor.listAllTables(rid).forEach(t -> numbers.put(t.id(), t.number()));
        return numbers;
    }
}
