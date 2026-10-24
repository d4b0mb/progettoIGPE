package it.ristomanager.cloud.order;

import it.ristomanager.cloud.common.Allergen;
import it.ristomanager.cloud.menu.MenuTypes.Course;
import it.ristomanager.cloud.order.OrderTypes.ItemView;
import it.ristomanager.cloud.order.OrderTypes.Priority;
import it.ristomanager.cloud.order.OrderTypes.Status;
import it.ristomanager.cloud.order.OrderTypes.TicketView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OrderRepository {

    /** A ticket row before its lines are attached. */
    private record Row(UUID id, String code, UUID tableId, Integer tableNumber, UUID reservationId, String guestName,
                       List<Allergen> guestAllergens, Priority priority, Status status, String note,
                       Instant createdAt, Instant acknowledgedAt, Instant readyAt, Instant servedAt) {
    }

    private static final String TICKET_COLS = "id, code, table_id, table_number, reservation_id, guest_name, "
            + "guest_allergens, priority, status, note, created_at, acknowledged_at, ready_at, served_at";

    private static final String QUEUE_ORDER = "order by case priority when 'VIP' then 3 when 'URGENT' then 2 "
            + "when 'HIGH' then 1 else 0 end desc, created_at";

    private final JdbcClient jdbc;

    OrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Next running number of the day, atomic even when many orders arrive at once. */
    public int nextNumber(UUID rid, LocalDate day) {
        return jdbc.sql("insert into ticket_counter (restaurant_id, day, last_number) values (:rid, :day, 1) "
                        + "on conflict (restaurant_id, day) do update set last_number = ticket_counter.last_number + 1 "
                        + "returning last_number")
                .param("rid", rid).param("day", day).query(Integer.class).single();
    }

    public UUID insertTicket(UUID rid, UUID userId, LocalDate day, String code, UUID tableId, Integer tableNumber,
                             UUID reservationId, String guestName, List<Allergen> guestAllergens,
                             Priority priority, String note) {
        return jdbc.sql("insert into kitchen_ticket (restaurant_id, ticket_day, code, table_id, table_number, "
                        + "reservation_id, guest_name, guest_allergens, priority, note, created_by) "
                        + "values (:rid, :day, :code, :table, :number, :res, :guest, cast(:allergens as text[]), "
                        + ":priority, :note, :by) returning id")
                .param("rid", rid).param("day", day).param("code", code).param("table", tableId)
                .param("number", tableNumber).param("res", reservationId).param("guest", guestName)
                .param("allergens", names(guestAllergens)).param("priority", priority.name())
                .param("note", note).param("by", userId)
                .query(UUID.class).single();
    }

    public void insertItem(UUID rid, UUID ticketId, UUID menuItemId, String name, int quantity, Course course,
                           List<Allergen> allergens, String note) {
        jdbc.sql("insert into ticket_item (restaurant_id, ticket_id, menu_item_id, name, quantity, course, allergens, note) "
                        + "values (:rid, :ticket, :menu, :name, :qty, :course, cast(:allergens as text[]), :note)")
                .param("rid", rid).param("ticket", ticketId).param("menu", menuItemId).param("name", name)
                .param("qty", quantity).param("course", course.name()).param("allergens", names(allergens))
                .param("note", note).update();
    }

    public Optional<TicketView> find(UUID rid, UUID id) {
        List<Row> rows = jdbc.sql("select " + TICKET_COLS + " from kitchen_ticket where restaurant_id = :rid and id = :id")
                .param("rid", rid).param("id", id).query(OrderRepository::row).list();
        return attach(rid, rows).stream().findFirst();
    }

    /** Everything still on the kitchen screen: highest priority first, then oldest first. */
    public List<TicketView> listActive(UUID rid) {
        List<Row> rows = jdbc.sql("select " + TICKET_COLS + " from kitchen_ticket where restaurant_id = :rid "
                        + "and status in ('NEW', 'PREPARING', 'READY') " + QUEUE_ORDER)
                .param("rid", rid).query(OrderRepository::row).list();
        return attach(rid, rows);
    }

    /** Moves a ticket only if it is still in the status the caller saw. */
    public boolean transition(UUID rid, UUID id, Status from, Status to) {
        return jdbc.sql("update kitchen_ticket set status = :to, "
                        + "acknowledged_at = case when :to = 'PREPARING' then coalesce(acknowledged_at, now()) else acknowledged_at end, "
                        + "ready_at = case when :to = 'READY' then now() else ready_at end, "
                        + "served_at = case when :to = 'SERVED' then now() else served_at end "
                        + "where restaurant_id = :rid and id = :id and status = :from")
                .param("rid", rid).param("id", id).param("from", from.name()).param("to", to.name())
                .update() == 1;
    }

    private List<TicketView> attach(UUID rid, List<Row> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        UUID[] ids = rows.stream().map(Row::id).toArray(UUID[]::new);
        Map<UUID, List<ItemView>> itemsByTicket = new HashMap<>();
        Map<UUID, Row> byId = new HashMap<>();
        rows.forEach(r -> byId.put(r.id(), r));
        jdbc.sql("select ticket_id, id, menu_item_id, name, quantity, course, allergens, note from ticket_item "
                        + "where restaurant_id = :rid and ticket_id = any(:ids) order by name")
                .param("rid", rid).param("ids", ids)
                .query((rs, n) -> {
                    UUID ticketId = rs.getObject("ticket_id", UUID.class);
                    List<Allergen> dish = allergens(rs);
                    Set<Allergen> guest = guestSet(byId.get(ticketId).guestAllergens());
                    return Map.entry(ticketId, new ItemView(rs.getObject("id", UUID.class),
                            rs.getObject("menu_item_id", UUID.class), rs.getString("name"), rs.getInt("quantity"),
                            Course.valueOf(rs.getString("course")), dish, OrderTypes.conflictsOf(dish, guest),
                            rs.getString("note")));
                }).list()
                .forEach(e -> itemsByTicket.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));

        List<TicketView> views = new ArrayList<>();
        for (Row r : rows) {
            List<ItemView> items = itemsByTicket.getOrDefault(r.id(), List.of()).stream()
                    .sorted(java.util.Comparator.comparingInt((ItemView i) -> i.course().order()))
                    .toList();
            boolean conflicts = items.stream().anyMatch(i -> !i.conflicts().isEmpty());
            views.add(new TicketView(r.id(), r.code(), r.tableId(), r.tableNumber(), r.reservationId(), r.guestName(),
                    r.guestAllergens(), r.priority(), r.status(), r.note(), conflicts, items,
                    r.createdAt(), r.acknowledgedAt(), r.readyAt(), r.servedAt()));
        }
        return views;
    }

    private static Set<Allergen> guestSet(List<Allergen> list) {
        return list.isEmpty() ? EnumSet.noneOf(Allergen.class) : EnumSet.copyOf(list);
    }

    private static String[] names(List<Allergen> allergens) {
        return allergens == null ? new String[0]
                : allergens.stream().map(Allergen::name).distinct().sorted().toArray(String[]::new);
    }

    private static List<Allergen> allergens(ResultSet rs) throws SQLException {
        return Arrays.stream((String[]) rs.getArray("allergens").getArray()).map(Allergen::valueOf).toList();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    private static Row row(ResultSet rs, int n) throws SQLException {
        int number = rs.getInt("table_number");
        Integer tableNumber = rs.wasNull() ? null : number;
        return new Row(rs.getObject("id", UUID.class), rs.getString("code"), rs.getObject("table_id", UUID.class),
                tableNumber, rs.getObject("reservation_id", UUID.class), rs.getString("guest_name"),
                Arrays.stream((String[]) rs.getArray("guest_allergens").getArray()).map(Allergen::valueOf).toList(),
                Priority.valueOf(rs.getString("priority")), Status.valueOf(rs.getString("status")), rs.getString("note"),
                instant(rs, "created_at"), instant(rs, "acknowledged_at"), instant(rs, "ready_at"), instant(rs, "served_at"));
    }
}
