package it.ristomanager.cloud.menu;

import it.ristomanager.cloud.common.Allergen;
import it.ristomanager.cloud.common.ApiException;
import it.ristomanager.cloud.menu.MenuTypes.Course;
import it.ristomanager.cloud.menu.MenuTypes.MenuItem;
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

/** Everyone on staff can read the menu; owner and manager edit it. */
@RestController
@RequestMapping("/api/v1/menu/items")
class MenuController {

    record MenuItemRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 500) String description,
            @NotNull Course course,
            @Size(max = 60) String section,
            @Min(0) @Max(1_000_000) Integer priceCents,
            List<Allergen> allergens,
            Boolean available) {
    }

    private final MenuRepository menu;

    MenuController(MenuRepository menu) {
        this.menu = menu;
    }

    @GetMapping
    List<MenuItem> list(@AuthenticationPrincipal Jwt jwt) {
        return menu.list(CurrentUser.from(jwt).restaurantId());
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
    ResponseEntity<MenuItem> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody MenuItemRequest req) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(menu.insert(CurrentUser.from(jwt).restaurantId(), toItem(req)));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("A menu item with this name already exists");
        }
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
    MenuItem update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody MenuItemRequest req) {
        try {
            return menu.update(CurrentUser.from(jwt).restaurantId(), id, toItem(req))
                    .orElseThrow(() -> ApiException.notFound("No such menu item"));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("A menu item with this name already exists");
        }
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        if (!menu.delete(CurrentUser.from(jwt).restaurantId(), id)) {
            throw ApiException.notFound("No such menu item");
        }
        return ResponseEntity.noContent().build();
    }

    private static MenuItem toItem(MenuItemRequest r) {
        return new MenuItem(null, r.name().trim(), r.description() == null ? "" : r.description().trim(), r.course(),
                r.section() == null ? "" : r.section().trim(), r.priceCents(), r.allergens() == null ? List.of() : r.allergens(), r.available() == null || r.available());
    }
}
