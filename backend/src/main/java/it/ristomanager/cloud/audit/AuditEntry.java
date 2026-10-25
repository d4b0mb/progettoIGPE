package it.ristomanager.cloud.audit;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** One line of the activity log, as the owner sees it. */
public record AuditEntry(
        UUID id,
        Instant occurredAt,
        @Schema(nullable = true) UUID actorUserId,
        @Schema(description = "Display name of the person, or the email that was tried for a failed sign-in") String actorName,
        String action,
        @Schema(nullable = true) String targetType,
        @Schema(nullable = true) String targetId,
        @Schema(allowableValues = {"SUCCESS", "FAILURE"}) String outcome,
        Map<String, String> details,
        @Schema(nullable = true) String ip) {
}
