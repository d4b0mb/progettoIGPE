package it.ristomanager.cloud.user;

import java.util.UUID;

public record AppUser(
        UUID id,
        UUID restaurantId,
        String email,
        String passwordHash,
        String displayName,
        Role role,
        boolean active) {
}
