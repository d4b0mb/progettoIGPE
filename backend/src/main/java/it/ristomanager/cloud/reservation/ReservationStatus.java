package it.ristomanager.cloud.reservation;

/** Life of a reservation. */
public enum ReservationStatus {
    PENDING,
    CONFIRMED,
    ARRIVED,
    COMPLETED,
    CANCELLED,
    NO_SHOW;

    /** Only live reservations hold their table; finished or cancelled ones free it. */
    public boolean occupiesTable() {
        return this == PENDING || this == CONFIRMED || this == ARRIVED;
    }
}
