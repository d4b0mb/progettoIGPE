package it.ristomanager.cloud.restaurant;

public final class RestaurantTypes {

    private RestaurantTypes() {
    }

    /** How the app looks for this restaurant's staff. */
    public enum Theme {
        CLASSIC, DUODECIM
    }

    public record RestaurantView(String name, Theme theme) {
    }
}
