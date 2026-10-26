package it.ristomanager.cloud.assistant;

import it.ristomanager.cloud.common.Allergen;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The data the assistant's tools read and the two actions it can ask for. An interface so tests can stub it.
 *
 * The restaurant id (and the user id) always come from the signed-in person's token, never from what the model
 * wrote. Read methods return small, capped lists: the model's context is short and guests' data should not be
 * handed over in bulk.
 */
public interface AssistantData {

    /** The restaurant's own time zone: "tonight" and "21:00" mean what the staff mean there. */
    ZoneId timezone(UUID restaurantId);

    /**
     * At most 15 reservations ordered by start. With a date: that day. Without: from today onwards. With a name: only
     * guests whose name contains it (accents and case ignored). All statuses are included.
     */
    List<ReservationRow> findReservations(UUID restaurantId, LocalDate dateOrNull, String nameOrNull);

    /**
     * At most 15 pending or confirmed reservations whose guest name contains the given name (accents and case ignored).
     * With a date: that day. Without: today and the following 13 days.
     */
    List<ReservationRow> findCancellable(UUID restaurantId, LocalDate dateOrNull, String name);

    /** Every table with what it looks like right now. */
    List<TableRow> floorStatus(UUID restaurantId);

    /** At most 20 tickets still on the kitchen screen. */
    List<TicketRow> kitchenQueue(UUID restaurantId);

    /** At most 8 menu items whose name contains the query (accents and case ignored). */
    List<MenuRow> searchMenu(UUID restaurantId, String query);

    /** The smallest table that fits the party and is free for the usual two hours from the start, if there is one. */
    Optional<TableChoice> availableTable(UUID restaurantId, Instant startsAt, int partySize);

    /** Applies the reservation rules without writing anything. Throws an ApiException with the reason when the draft is not acceptable. */
    void validateDraft(UUID restaurantId, ReservationDraft draft);

    /** Creates a confirmed reservation. Only ever called after the person pressed Confirm. */
    BookingResult createReservation(UUID restaurantId, UUID userId, ReservationDraft draft);

    /** Cancels a reservation. Only ever called after the person pressed Confirm. */
    BookingResult cancelReservation(UUID restaurantId, UUID reservationId);

    /** A reservation as the tools show it. Date and time are in the restaurant's time zone; notes are cut to 160 characters. */
    record ReservationRow(UUID id, String guestName, int partySize, LocalDate date, String time, String status,
                          Integer tableNumber, List<String> allergens, String notes) {
    }

    record TableRow(int number, int seats, String status) {
    }

    record TicketLine(int quantity, String name) {
    }

    record TicketRow(String code, Integer tableNumber, String status, List<TicketLine> items, boolean allergyAlarm) {
    }

    /** What the menu says about one item. Allergens are the declared ones; nothing here decides whether a dish is safe. */
    record MenuRow(String name, Integer priceCents, String course, List<String> allergens, String description) {
    }

    record TableChoice(UUID id, int number, int seats) {
    }

    /** What to book. The status is always CONFIRMED; the end time is the usual default. */
    record ReservationDraft(String guestName, String phone, Instant startsAt, int partySize, UUID tableId,
                            List<Allergen> allergens, String notes) {
    }

    /** What an executed action touched, with date and time in the restaurant's time zone. */
    record BookingResult(UUID id, String guestName, int partySize, String date, String time, Integer tableNumber) {
    }
}
