package it.ristomanager.cloud.user;

import java.util.UUID;

/** What the API shows about a user. Never includes the password hash. */
public record UserView(UUID id, UUID restaurantId, String email, String displayName, Role role, boolean active) {

    public static UserView of(AppUser u) {
        return new UserView(u.id(), u.restaurantId(), u.email(), u.displayName(), u.role(), u.active());
    }
}
