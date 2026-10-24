package it.ristomanager.cloud.user;

import it.ristomanager.cloud.auth.AuthService;
import it.ristomanager.cloud.common.ApiException;
import it.ristomanager.cloud.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Staff accounts of the caller's own restaurant. Owner only. */
@RestController
@RequestMapping("/api/v1/staff")
@PreAuthorize("hasRole('OWNER')")
class StaffController {

    record CreateStaffRequest(
            @NotBlank @Size(max = 120) String displayName,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 10, max = 72, message = "must be between 10 and 72 characters") String password,
            @NotNull Role role) {
    }

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AuthService auth;

    StaffController(UserRepository users, PasswordEncoder encoder, AuthService auth) {
        this.users = users;
        this.encoder = encoder;
        this.auth = auth;
    }

    @GetMapping
    List<UserView> list(@AuthenticationPrincipal Jwt jwt) {
        return users.listByRestaurant(CurrentUser.from(jwt).restaurantId()).stream().map(UserView::of).toList();
    }

    @PostMapping
    ResponseEntity<UserView> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateStaffRequest req) {
        if (req.role() == Role.OWNER) {
            throw ApiException.badRequest("A restaurant has one owner, created at sign-up");
        }
        CurrentUser me = CurrentUser.from(jwt);
        String email = AuthService.normalizeEmail(req.email());
        if (users.emailExists(email)) {
            throw ApiException.conflict("An account with this email already exists");
        }
        try {
            AppUser created = users.insert(me.restaurantId(), email, encoder.encode(req.password()),
                    req.displayName().trim(), req.role());
            return ResponseEntity.status(HttpStatus.CREATED).body(UserView.of(created));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("An account with this email already exists");
        }
    }

    @DeleteMapping("/{id}")
    @Transactional
    ResponseEntity<Void> deactivate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        CurrentUser me = CurrentUser.from(jwt);
        if (me.userId().equals(id)) {
            throw ApiException.badRequest("You cannot deactivate your own account");
        }
        if (!users.deactivate(me.restaurantId(), id)) {
            throw ApiException.notFound("No such staff member");
        }
        auth.revokeAllSessions(id);
        return ResponseEntity.noContent().build();
    }
}
