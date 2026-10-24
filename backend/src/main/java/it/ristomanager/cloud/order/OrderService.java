package it.ristomanager.cloud.order;

import it.ristomanager.cloud.common.Allergen;
import it.ristomanager.cloud.common.ApiException;
import it.ristomanager.cloud.floor.FloorRepository;
import it.ristomanager.cloud.floor.FloorTypes.DiningTable;
import it.ristomanager.cloud.menu.MenuRepository;
import it.ristomanager.cloud.menu.MenuTypes.MenuItem;
import it.ristomanager.cloud.order.OrderTypes.LineRequest;
import it.ristomanager.cloud.order.OrderTypes.OrderRequest;
import it.ristomanager.cloud.order.OrderTypes.Priority;
import it.ristomanager.cloud.order.OrderTypes.Status;
import it.ristomanager.cloud.order.OrderTypes.TicketView;
import it.ristomanager.cloud.reservation.ReservationRepository;
import it.ristomanager.cloud.reservation.ReservationTypes.Reservation;
import it.ristomanager.cloud.restaurant.RestaurantRepository;
import it.ristomanager.cloud.user.Role;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    static final int MAX_LINES = 50;
    static final int MAX_QUANTITY = 99;

    private final OrderRepository orders;
    private final MenuRepository menu;
    private final FloorRepository floor;
    private final ReservationRepository reservations;
    private final RestaurantRepository restaurants;
    private final TicketEvents events;

    OrderService(OrderRepository orders, MenuRepository menu, FloorRepository floor,
                 ReservationRepository reservations, RestaurantRepository restaurants, TicketEvents events) {
        this.orders = orders;
        this.menu = menu;
        this.floor = floor;
        this.reservations = reservations;
        this.restaurants = restaurants;
        this.events = events;
    }

    @Transactional
    public TicketView create(UUID rid, UUID userId, OrderRequest req) {
        if (req.tableId() == null) {
            throw ApiException.invalid("tableId", "Choose a table");
        }
        List<LineRequest> lines = req.items() == null ? List.of() : req.items();
        if (lines.isEmpty() || lines.size() > MAX_LINES) {
            throw ApiException.invalid("items", "An order needs between 1 and " + MAX_LINES + " lines");
        }
        DiningTable table = floor.findTable(rid, req.tableId())
                .orElseThrow(() -> ApiException.invalid("tableId", "This table does not exist"));

        // The kitchen must trust the allergens on the ticket, so they come from the menu, never from the client.
        Map<UUID, MenuItem> dishes = new HashMap<>();
        menu.findAll(rid, lines.stream().map(LineRequest::menuItemId).filter(java.util.Objects::nonNull).toList())
                .forEach(m -> dishes.put(m.id(), m));
        for (LineRequest line : lines) {
            MenuItem dish = line.menuItemId() == null ? null : dishes.get(line.menuItemId());
            if (dish == null) {
                throw ApiException.invalid("items", "A dish in this order is not on the menu");
            }
            if (!dish.available()) {
                throw ApiException.invalid("items", dish.name() + " is not available");
            }
            if (line.quantity() == null || line.quantity() < 1 || line.quantity() > MAX_QUANTITY) {
                throw ApiException.invalid("items", "Quantity must be between 1 and " + MAX_QUANTITY);
            }
            if (line.note() != null && line.note().length() > 200) {
                throw ApiException.invalid("items", "A line note is too long");
            }
        }
        if (req.note() != null && req.note().length() > 500) {
            throw ApiException.invalid("note", "The note is too long");
        }

        // Link the party seated at the table, so their declared allergies reach the kitchen.
        Reservation party = req.reservationId() != null
                ? reservations.find(rid, req.reservationId())
                        .orElseThrow(() -> ApiException.invalid("reservationId", "This reservation does not exist"))
                : reservations.findArrivedAt(rid, table.id(), Instant.now()).orElse(null);
        List<Allergen> guestAllergens = party == null ? List.of() : party.allergens();

        LocalDate day = LocalDate.now(restaurants.timezone(rid));
        String code = "C-%03d".formatted(orders.nextNumber(rid, day));
        Priority priority = req.priority() == null ? Priority.NORMAL : req.priority();
        UUID ticketId = orders.insertTicket(rid, userId, day, code, table.id(), table.number(),
                party == null ? null : party.id(), party == null ? "" : party.guestName(), guestAllergens,
                priority, req.note() == null ? "" : req.note().trim());
        for (LineRequest line : lines) {
            MenuItem dish = dishes.get(line.menuItemId());
            orders.insertItem(rid, ticketId, dish.id(), dish.name(), line.quantity(), dish.course(), dish.allergens(),
                    line.note() == null ? "" : line.note().trim());
        }
        TicketView view = orders.find(rid, ticketId).orElseThrow();
        events.publish(rid, "ticket-created", view);
        return view;
    }

    public List<TicketView> active(UUID rid) {
        return orders.listActive(rid);
    }

    public TicketView get(UUID rid, UUID id) {
        return orders.find(rid, id).orElseThrow(() -> ApiException.notFound("No such order"));
    }

    @Transactional
    public TicketView changeStatus(UUID rid, UUID id, Role role, Status next) {
        if (next == null) {
            throw ApiException.invalid("status", "Choose a status");
        }
        TicketView current = get(rid, id);
        if (!next.mayBeSetBy(role)) {
            throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,
                    "Your role cannot set an order to " + next);
        }
        if (!current.status().canMoveTo(next)) {
            throw ApiException.conflict("An order that is " + current.status() + " cannot become " + next);
        }
        // If someone else moved it between our read and this write, nothing matches and we say so.
        if (!orders.transition(rid, id, current.status(), next)) {
            throw ApiException.conflict("This order was just changed by someone else; reload it");
        }
        TicketView updated = get(rid, id);
        events.publish(rid, "ticket-updated", updated);
        return updated;
    }
}
