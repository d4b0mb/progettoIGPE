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
        DRINK(0), STARTER(1), FIRST(2), SECOND(3), SIDE(4), DESSERT(5);

        private final int order;

        Course(int order) {
            this.order = order;
        }

        public int order() {
            return order;
        }
    }

    public record MenuItem(UUID id, String name, String description, Course course, @Schema(nullable = true) Integer priceCents,
                           List<Allergen> allergens, boolean available) {
    }
}
