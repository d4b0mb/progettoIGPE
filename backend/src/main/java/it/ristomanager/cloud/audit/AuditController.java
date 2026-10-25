package it.ristomanager.cloud.audit;

import it.ristomanager.cloud.common.ApiException;
import it.ristomanager.cloud.security.CurrentUser;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The activity log. Owner only. */
@RestController
@RequestMapping("/api/v1/audit")
@PreAuthorize("hasRole('OWNER')")
class AuditController {

    private static final int MAX_PAGE = 500;
    private static final Duration DEFAULT_WINDOW = Duration.ofDays(7);

    private final AuditRepository repository;

    AuditController(AuditRepository repository) {
        this.repository = repository;
    }

    /**
     * Newest first. Defaults to the last 7 days. To read further back, pass {@code before} with the time
     * of the last entry received.
     */
    @GetMapping
    List<AuditEntry> list(@AuthenticationPrincipal Jwt jwt,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime before,
                          @RequestParam(required = false) UUID actorId,
                          @RequestParam(defaultValue = "200") int limit) {
        if (limit < 1 || limit > MAX_PAGE) {
            throw ApiException.invalid("limit", "limit must be between 1 and " + MAX_PAGE);
        }
        Instant end = to == null ? Instant.now().plusSeconds(60) : to.toInstant();
        Instant start = from == null ? end.minus(DEFAULT_WINDOW) : from.toInstant();
        if (!start.isBefore(end)) {
            throw ApiException.invalid("from", "from must be before to");
        }
        return repository.find(CurrentUser.from(jwt).restaurantId(), start, end,
                before == null ? null : before.toInstant(), actorId, limit);
    }
}
