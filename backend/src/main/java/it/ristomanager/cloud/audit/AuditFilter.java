package it.ristomanager.cloud.audit;

import it.ristomanager.cloud.security.CurrentUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Records every successful change a signed-in person makes (anything but a read), and every time
 * someone is refused access. New endpoints are covered automatically; the ones that have richer
 * entries of their own (sign-in, staff, orders) are skipped here so they are not logged twice.
 */
public class AuditFilter extends OncePerRequestFilter {

    private static final Set<String> CHANGES = Set.of("POST", "PUT", "PATCH", "DELETE");

    /** Endpoints whose services write their own, more detailed entry. */
    private static final Set<String> LOGGED_ELSEWHERE_PREFIXES = Set.of(
            "/api/v1/auth/", "/api/v1/staff", "/api/v1/orders", "/api/v1/me");

    /** Friendly names for the common actions. Anything not listed is logged as "METHOD /path". */
    private static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("POST /api/v1/rooms", "ROOM_CREATED"),
            Map.entry("PUT /api/v1/rooms/{id}", "ROOM_UPDATED"),
            Map.entry("DELETE /api/v1/rooms/{id}", "ROOM_DELETED"),
            Map.entry("POST /api/v1/rooms/{roomId}/tables", "TABLE_CREATED"),
            Map.entry("PUT /api/v1/tables/{id}", "TABLE_UPDATED"),
            Map.entry("PATCH /api/v1/tables/{id}/service-status", "TABLE_SERVICE_STATUS_CHANGED"),
            Map.entry("DELETE /api/v1/tables/{id}", "TABLE_DELETED"),
            Map.entry("POST /api/v1/rooms/{roomId}/obstacles", "ELEMENT_CREATED"),
            Map.entry("PUT /api/v1/obstacles/{id}", "ELEMENT_UPDATED"),
            Map.entry("DELETE /api/v1/obstacles/{id}", "ELEMENT_DELETED"),
            Map.entry("POST /api/v1/reservations", "RESERVATION_CREATED"),
            Map.entry("PUT /api/v1/reservations/{id}", "RESERVATION_UPDATED"),
            Map.entry("PATCH /api/v1/reservations/{id}/status", "RESERVATION_STATUS_CHANGED"),
            Map.entry("DELETE /api/v1/reservations/{id}", "RESERVATION_DELETED"),
            Map.entry("POST /api/v1/menu/items", "MENU_ITEM_CREATED"),
            Map.entry("PUT /api/v1/menu/items/{id}", "MENU_ITEM_UPDATED"),
            Map.entry("DELETE /api/v1/menu/items/{id}", "MENU_ITEM_DELETED"));

    private final AuditLog audit;

    public AuditFilter(AuditLog audit) {
        this.audit = audit;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (AccessDeniedException refused) {
            // A role check on the endpoint said no. The exception travels up to Spring Security, which
            // turns it into a 403, so it has to be caught here to be seen.
            recordDenied(request);
            throw refused;
        }

        int status = response.getStatus();
        String path = request.getRequestURI();
        if (AuditLog.currentActor().isEmpty()) {
            return;
        }
        if (status == 403) {
            recordDenied(request);
            return;
        }
        if (!CHANGES.contains(request.getMethod()) || status < 200 || status >= 300) {
            return;
        }
        if (LOGGED_ELSEWHERE_PREFIXES.stream().anyMatch(path::startsWith)) {
            return;
        }
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String key = request.getMethod() + " " + pattern;
        @SuppressWarnings("unchecked")
        Map<String, String> variables = (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        Map<String, String> details = new LinkedHashMap<>();
        details.put("path", path);
        String targetId = null;
        if (variables != null && !variables.isEmpty()) {
            targetId = variables.values().iterator().next();
        }
        audit.record(NAMES.getOrDefault(key, key), targetType(path), targetId, details);
    }

    private void recordDenied(HttpServletRequest request) {
        AuditLog.currentActor().ifPresent(actor -> audit.recordFor(actor.restaurantId(), actor.userId(), "", "ACCESS_DENIED",
                null, null, "FAILURE", Map.of("method", request.getMethod(), "path", request.getRequestURI())));
    }

    private static String targetType(String path) {
        String[] parts = path.split("/");
        return parts.length > 3 ? parts[3] : null; // /api/v1/<thing>/...
    }
}
