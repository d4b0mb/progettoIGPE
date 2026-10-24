package it.ristomanager.cloud.auth;

import it.ristomanager.cloud.user.UserView;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request and response shapes of the auth endpoints. */
final class AuthDtos {

    private AuthDtos() {
    }

    /** bcrypt only looks at the first 72 bytes, so longer passwords are refused rather than silently truncated. */
    record SignupRequest(
            @NotBlank @Size(max = 120) String restaurantName,
            @NotBlank @Size(max = 120) String ownerName,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 10, max = 72, message = "must be between 10 and 72 characters") String password) {
    }

    record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
    }

    record TokenResponse(String accessToken, String tokenType, long expiresInSeconds, UserView user) {
    }
}
