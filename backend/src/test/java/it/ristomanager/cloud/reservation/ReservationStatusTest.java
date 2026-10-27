package it.ristomanager.cloud.reservation;

import static it.ristomanager.cloud.reservation.ReservationStatus.ARRIVED;
import static it.ristomanager.cloud.reservation.ReservationStatus.CANCELLED;
import static it.ristomanager.cloud.reservation.ReservationStatus.COMPLETED;
import static it.ristomanager.cloud.reservation.ReservationStatus.CONFIRMED;
import static it.ristomanager.cloud.reservation.ReservationStatus.NO_SHOW;
import static it.ristomanager.cloud.reservation.ReservationStatus.PENDING;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReservationStatusTest {

    /** The only moves a waiter has on the day screen. Everything not listed here must be refused. */
    private static final Map<ReservationStatus, Set<ReservationStatus>> WAITER_MOVES = Map.of(
            PENDING, Set.of(ARRIVED, NO_SHOW),
            CONFIRMED, Set.of(ARRIVED, NO_SHOW),
            ARRIVED, Set.of(COMPLETED));

    @Test
    void aWaiterHasExactlyTheMovesTheDayScreenOffers() {
        for (ReservationStatus from : ReservationStatus.values()) {
            for (ReservationStatus to : ReservationStatus.values()) {
                boolean allowed = WAITER_MOVES.getOrDefault(from, Set.of()).contains(to);
                assertThat(from.waiterMayMoveTo(to)).as("%s -> %s", from, to).isEqualTo(allowed);
            }
        }
    }

    @Test
    void aWaiterCanNeverCancelOrReviveOrResetABooking() {
        for (ReservationStatus from : ReservationStatus.values()) {
            assertThat(from.waiterMayMoveTo(CANCELLED)).as("%s -> CANCELLED", from).isFalse();
            assertThat(CANCELLED.waiterMayMoveTo(from)).as("CANCELLED -> %s", from).isFalse();
            assertThat(from.waiterMayMoveTo(PENDING)).as("%s -> PENDING", from).isFalse();
            assertThat(from.waiterMayMoveTo(CONFIRMED)).as("%s -> CONFIRMED", from).isFalse();
        }
    }
}
