package it.ristomanager.cloud.security;

import it.ristomanager.cloud.user.Role;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Who is making this request, read from the verified token. Every
 * tenant-owned query takes {@link #restaurantId()} from here, never from
 * request input.
 */
public record CurrentUser(UUID userId, UUID restaurantId, Role role) {

    public static CurrentUser from(Jwt jwt) {
        return new CurrentUser(
                UUID.fromString(jwt.getSubject()),
                UUID.fromString(jwt.getClaimAsString("rid")),
                Role.valueOf(jwt.getClaimAsString("role")));
    }
}
