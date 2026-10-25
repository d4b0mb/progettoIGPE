package it.ristomanager.cloud.user;

import it.ristomanager.cloud.audit.AuditLog;
import it.ristomanager.cloud.auth.AuthService;
import it.ristomanager.cloud.common.ApiException;
import it.ristomanager.cloud.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
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
import org.springframework.web.bind.annotation.PatchMapping;
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

    record ResetPasswordRequest(@NotBlank String newPassword) {
    }

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AuthService auth;
    private final AuditLog audit;

    StaffController(UserRepository users, PasswordEncoder encoder, AuthService auth, AuditLog audit) {
        this.users = users;
        this.encoder = encoder;
        this.auth = auth;
        this.audit = audit;
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
            audit.record("STAFF_CREATED", "user", created.id().toString(), Map.of("email", created.email(), "role", created.role().name()));
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
        audit.record("STAFF_DEACTIVATED", "user", id.toString(), Map.of());
        return ResponseEntity.noContent().build();
    }

    /** The owner sets a new password for a staff member who forgot theirs. The staff member is signed out everywhere. */
    @PatchMapping("/{id}/password")
    @Transactional
    ResponseEntity<Void> resetPassword(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody ResetPasswordRequest req) {
        CurrentUser me = CurrentUser.from(jwt);
        if (me.userId().equals(id)) {
            throw ApiException.badRequest("Use \"change password\" for your own account");
        }
        PasswordPolicy.check(req.newPassword(), "newPassword");
        if (!users.updatePassword(me.restaurantId(), id, encoder.encode(req.newPassword()))) {
            throw ApiException.notFound("No such staff member");
        }
        auth.revokeAllSessions(id);
        audit.record("PASSWORD_RESET_BY_OWNER", "user", id.toString(), Map.of());
        return ResponseEntity.noContent().build();
    }
}
