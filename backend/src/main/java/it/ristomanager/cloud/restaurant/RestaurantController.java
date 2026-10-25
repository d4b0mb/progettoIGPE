package it.ristomanager.cloud.restaurant;

import it.ristomanager.cloud.common.ApiException;
import it.ristomanager.cloud.restaurant.RestaurantTypes.RestaurantView;
import it.ristomanager.cloud.restaurant.RestaurantTypes.Theme;
import it.ristomanager.cloud.security.CurrentUser;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The restaurant's own name and look. Everyone on staff reads it; only the owner changes the look. */
@RestController
@RequestMapping("/api/v1/restaurant")
class RestaurantController {

    record ThemeRequest(@NotNull Theme theme) {
    }

    private final RestaurantRepository restaurants;

    RestaurantController(RestaurantRepository restaurants) {
        this.restaurants = restaurants;
    }

    @GetMapping
    RestaurantView get(@AuthenticationPrincipal Jwt jwt) {
        return restaurants.view(CurrentUser.from(jwt).restaurantId())
                .orElseThrow(() -> ApiException.notFound("No such restaurant"));
    }

    @PutMapping("/theme")
    @PreAuthorize("hasRole('OWNER')")
    RestaurantView setTheme(@AuthenticationPrincipal Jwt jwt, @jakarta.validation.Valid @RequestBody ThemeRequest req) {
        var rid = CurrentUser.from(jwt).restaurantId();
        restaurants.setTheme(rid, req.theme());
        return get(jwt);
    }
}
