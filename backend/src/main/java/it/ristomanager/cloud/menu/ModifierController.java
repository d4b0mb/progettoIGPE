package it.ristomanager.cloud.menu;

import it.ristomanager.cloud.common.Allergen;
import it.ristomanager.cloud.common.ApiException;
import it.ristomanager.cloud.menu.MenuTypes.Course;
import it.ristomanager.cloud.menu.MenuTypes.Modifier;
import it.ristomanager.cloud.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Options for dishes (toppings, removals, variants): everyone on staff can read them, owner and manager edit them. */
@RestController
@RequestMapping("/api/v1/menu/modifiers")
class ModifierController {

    record ModifierRequest(
            @NotBlank @Size(max = 120) String name,
            @NotNull Course course,
            @Size(max = 60) String section,
            @Min(0) @Max(100_000) Integer priceCents,
            List<Allergen> allergens,
            Boolean available) {
    }

    private final ModifierRepository modifiers;

    ModifierController(ModifierRepository modifiers) {
        this.modifiers = modifiers;
    }

    @GetMapping
    List<Modifier> list(@AuthenticationPrincipal Jwt jwt) {
        return modifiers.list(CurrentUser.from(jwt).restaurantId());
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
    ResponseEntity<Modifier> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ModifierRequest req) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(modifiers.insert(CurrentUser.from(jwt).restaurantId(), toModifier(req)));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("This course already has an option with this name");
        }
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
    Modifier update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody ModifierRequest req) {
        try {
            return modifiers.update(CurrentUser.from(jwt).restaurantId(), id, toModifier(req))
                    .orElseThrow(() -> ApiException.notFound("No such option"));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("This course already has an option with this name");
        }
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        if (!modifiers.delete(CurrentUser.from(jwt).restaurantId(), id)) {
            throw ApiException.notFound("No such option");
        }
        return ResponseEntity.noContent().build();
    }

    private static Modifier toModifier(ModifierRequest r) {
        return new Modifier(null, r.name().trim(), r.course(), r.section() == null ? "" : r.section().trim(),
                r.priceCents() == null ? 0 : r.priceCents(), r.allergens() == null ? List.of() : r.allergens(),
                r.available() == null || r.available());
    }
}
