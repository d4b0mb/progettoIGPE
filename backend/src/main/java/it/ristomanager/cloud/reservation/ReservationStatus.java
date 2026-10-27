package it.ristomanager.cloud.reservation;

/** Life of a reservation. */
public enum ReservationStatus {
    PENDING,
    CONFIRMED,
    ARRIVED,
    COMPLETED,
    CANCELLED,
    NO_SHOW;

    /**
     * What a waiter may do from the day screen, and nothing else: mark a party that has not been seated yet as arrived or
     * as a no-show, and mark a seated party as completed. Cancelling, reviving a cancelled booking or setting any other
     * status is for the owner and managers (cancelling frees the table, so it can cause a double seating).
     */
    public boolean waiterMayMoveTo(ReservationStatus to) {
        return switch (this) {
            case PENDING, CONFIRMED -> to == ARRIVED || to == NO_SHOW;
            case ARRIVED -> to == COMPLETED;
            case COMPLETED, CANCELLED, NO_SHOW -> false;
        };
    }

    /** Only live reservations hold their table; finished or cancelled ones free it. */
    public boolean occupiesTable() {
        return this == PENDING || this == CONFIRMED || this == ARRIVED;
    }
}
