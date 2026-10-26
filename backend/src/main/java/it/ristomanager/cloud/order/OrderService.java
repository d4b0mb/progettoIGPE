package it.ristomanager.cloud.order;

import it.ristomanager.cloud.audit.AuditLog;
import it.ristomanager.cloud.common.Allergen;
import it.ristomanager.cloud.common.ApiException;
import it.ristomanager.cloud.floor.FloorRepository;
import it.ristomanager.cloud.floor.FloorTypes.DiningTable;
import it.ristomanager.cloud.menu.MenuRepository;
import it.ristomanager.cloud.menu.MenuTypes.MenuItem;
import it.ristomanager.cloud.menu.MenuTypes.Modifier;
import it.ristomanager.cloud.menu.ModifierRepository;
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
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    static final int MAX_LINES = 50;
    static final int MAX_QUANTITY = 99;
    static final int MAX_SERVINGS = 9;
    static final int MAX_MODIFIERS = 20;

    private final OrderRepository orders;
    private final MenuRepository menu;
    private final ModifierRepository modifierOptions;
    private final FloorRepository floor;
    private final ReservationRepository reservations;
    private final RestaurantRepository restaurants;
    private final TicketEvents events;
    private final AuditLog audit;

    OrderService(OrderRepository orders, MenuRepository menu, ModifierRepository modifierOptions, FloorRepository floor,
                 ReservationRepository reservations, RestaurantRepository restaurants, TicketEvents events,
                 AuditLog audit) {
        this.orders = orders;
        this.menu = menu;
        this.modifierOptions = modifierOptions;
        this.floor = floor;
        this.reservations = reservations;
        this.restaurants = restaurants;
        this.events = events;
        this.audit = audit;
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
            if (line.serving() != null && (line.serving() < 1 || line.serving() > MAX_SERVINGS)) {
                throw ApiException.invalid("items", "A serving number must be between 1 and " + MAX_SERVINGS);
            }
            if (line.note() != null && line.note().length() > 200) {
                throw ApiException.invalid("items", "A line note is too long");
            }
        }
        // Options come from the menu, never from the client: same rule as the dishes' allergens.
        // Kept in the menu's own order, so a ticket always lists the options the same way whatever order they were tapped in.
        Map<UUID, Modifier> options = new java.util.LinkedHashMap<>();
        modifierOptions.findAll(rid, lines.stream().flatMap(l -> chosen(l).stream()).distinct().toList())
                .forEach(m -> options.put(m.id(), m));
        for (LineRequest line : lines) {
            if (chosen(line).size() > MAX_MODIFIERS) {
                throw ApiException.invalid("items", "A dish can have at most " + MAX_MODIFIERS + " options");
            }
            for (UUID id : chosen(line)) {
                Modifier option = options.get(id);
                MenuItem dish = dishes.get(line.menuItemId());
                if (option == null) {
                    throw ApiException.invalid("items", "An option in this order is not on the menu");
                }
                if (!option.available()) {
                    throw ApiException.invalid("items", option.name() + " is not available");
                }
                if (option.course() != dish.course()) {
                    throw ApiException.invalid("items", option.name() + " cannot be added to " + dish.name());
                }
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
        int firstServing = lines.stream().mapToInt(OrderService::servingOf).min().orElse(1);
        UUID ticketId = orders.insertTicket(rid, userId, day, code, table.id(), table.number(),
                party == null ? null : party.id(), party == null ? "" : party.guestName(), guestAllergens,
                priority, req.note() == null ? "" : req.note().trim());
        for (LineRequest line : lines) {
            MenuItem dish = dishes.get(line.menuItemId());
            List<Modifier> picked = options.values().stream().filter(m -> chosen(line).contains(m.id())).toList();
            // The option's allergens join the dish's own, so the kitchen's allergy alarm sees what is really on the plate.
            List<Allergen> allergens = java.util.stream.Stream.concat(dish.allergens().stream(),
                    picked.stream().flatMap(m -> m.allergens().stream())).distinct().toList();
            orders.insertItem(rid, ticketId, dish.id(), dish.name(), line.quantity(), dish.course(), allergens,
                    line.note() == null ? "" : line.note().trim(), servingOf(line),
                    picked.stream().map(Modifier::name).toList(), picked.stream().mapToInt(Modifier::priceCents).sum());
        }
        orders.startAtServing(rid, ticketId, firstServing);
        TicketView view = orders.find(rid, ticketId).orElseThrow();
        events.publish(rid, "ticket-created", view);
        audit.record("ORDER_CREATED", "order", ticketId.toString(),
                Map.of("code", view.code(), "table", String.valueOf(table.number()), "lines", String.valueOf(lines.size())));
        return view;
    }

    private static List<UUID> chosen(LineRequest line) {
        return line.modifierIds() == null ? List.of() : line.modifierIds().stream().distinct().toList();
    }

    private static int servingOf(LineRequest line) {
        return line.serving() == null ? 1 : line.serving();
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
        audit.record("ORDER_STATUS_CHANGED", "order", id.toString(),
                Map.of("code", updated.code(), "from", current.status().name(), "to", next.name()));
        return updated;
    }
}
