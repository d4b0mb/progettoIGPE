package it.ristomanager.cloud.order;

import io.swagger.v3.oas.annotations.media.Schema;
import it.ristomanager.cloud.common.Allergen;
import it.ristomanager.cloud.menu.MenuTypes.Course;
import it.ristomanager.cloud.user.Role;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class OrderTypes {

    private OrderTypes() {
    }

    /** How loudly the kitchen screen should shout. Higher level is cooked first. */
    public enum Priority {
        NORMAL(0), HIGH(1), URGENT(2), VIP(3);

        private final int level;

        Priority(int level) {
            this.level = level;
        }

        public int level() {
            return level;
        }
    }

    /** From the dining room to the kitchen and back. */
    public enum Status {
        NEW, PREPARING, READY, SERVED, CANCELLED;

        /** Tickets that still need attention appear on the kitchen screen. */
        public boolean isActive() {
            return this == NEW || this == PREPARING || this == READY;
        }

        public boolean canMoveTo(Status next) {
            return switch (this) {
                case NEW -> next == PREPARING || next == CANCELLED;
                case PREPARING -> next == READY || next == CANCELLED;
                // The kitchen may pull a ticket back if it was marked ready by mistake.
                case READY -> next == SERVED || next == PREPARING;
                case SERVED, CANCELLED -> false;
            };
        }

        /** Who may set this status: the kitchen cooks, the floor serves and cancels, managers can do anything. */
        public boolean mayBeSetBy(Role role) {
            return switch (role) {
                case OWNER, MANAGER -> true;
                case KITCHEN -> this == PREPARING || this == READY;
                case WAITER -> this == SERVED || this == CANCELLED;
            };
        }
    }

    /**
     * {@code serving} is 1 to 9 (blank means 1): lines of serving 2 reach the kitchen after serving 1 is served.
     * {@code modifierIds} are toppings and options of the dish's course; their allergens join the dish's own.
     */
    public record LineRequest(UUID menuItemId, Integer quantity, String note, Integer serving, List<UUID> modifierIds) {
    }

    public record OrderRequest(UUID tableId, UUID reservationId, Priority priority, String note, List<LineRequest> items) {
    }

    public record ItemView(UUID id, @Schema(nullable = true) UUID menuItemId, String name, int quantity, Course course,
                           List<Allergen> allergens, List<Allergen> conflicts, String note, int serving,
                           List<String> modifiers, int modifierCents) {
    }

    /**
     * An order as the kitchen sees it. {@code conflicts} on a line are the allergens
     * the dish contains that the guest declared they cannot eat: the real alarm.
     */
    public record TicketView(UUID id, String code, @Schema(nullable = true) UUID tableId,
                             @Schema(nullable = true) Integer tableNumber, @Schema(nullable = true) UUID reservationId,
                             String guestName, List<Allergen> guestAllergens, Priority priority, Status status,
                             String note, boolean hasAllergyConflicts, List<ItemView> items,
                             int currentServing, int servingCount, Instant servingSince,
                             Instant createdAt, @Schema(nullable = true) Instant acknowledgedAt,
                             @Schema(nullable = true) Instant readyAt, @Schema(nullable = true) Instant servedAt) {
    }

    /** Allergens of a dish that the guest cannot eat. */
    public static List<Allergen> conflictsOf(List<Allergen> dish, Set<Allergen> guest) {
        return dish.stream().filter(guest::contains).toList();
    }
}
