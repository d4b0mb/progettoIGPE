package it.ristomanager.cloud.reservation;

import it.ristomanager.cloud.common.Allergen;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class ReservationTypes {

    private ReservationTypes() {
    }

    /** What a client sends to create or change a reservation. */
    public record Draft(
            String guestName,
            String phone,
            String email,
            OffsetDateTime startsAt,
            Integer durationMinutes,
            Integer partySize,
            Integer highChairs,
            Integer strollerSpaces,
            UUID tableId,
            ReservationStatus status,
            List<Allergen> allergens,
            String notes) {
    }

    /** A stored reservation. */
    public record Reservation(
            UUID id,
            UUID tableId,
            String guestName,
            String phone,
            String email,
            Instant startsAt,
            int durationMinutes,
            int partySize,
            int highChairs,
            int strollerSpaces,
            ReservationStatus status,
            List<Allergen> allergens,
            String notes) {

        public Instant endsAt() {
            return startsAt.plusSeconds(durationMinutes * 60L);
        }

        public Draft toDraft(ReservationStatus newStatus) {
            return new Draft(guestName, phone, email, startsAt.atOffset(java.time.ZoneOffset.UTC), durationMinutes,
                    partySize, highChairs, strollerSpaces, tableId, newStatus, allergens, notes);
        }
    }
}
