package it.ristomanager.cloud.menu;

import io.swagger.v3.oas.annotations.media.Schema;
import it.ristomanager.cloud.common.Allergen;
import java.util.List;
import java.util.UUID;

public final class MenuTypes {

    private MenuTypes() {
    }

    /** The order dishes leave the kitchen in. Drinks go first. */
    public enum Course {
        DRINK(0), STARTER(1), PIZZA(2), FIRST(3), SECOND(4), SIDE(5), DESSERT(6);

        private final int order;

        Course(int order) {
            this.order = order;
        }

        public int order() {
            return order;
        }
    }

    /** {@code section} groups many items of one course on screen ("Birre", "Vini"); it may be empty. */
    public record MenuItem(UUID id, String name, String description, Course course, String section,
                           @Schema(nullable = true) Integer priceCents, List<Allergen> allergens, boolean available) {
    }
}
