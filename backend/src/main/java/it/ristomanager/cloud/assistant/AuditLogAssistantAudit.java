package it.ristomanager.cloud.assistant;

import it.ristomanager.cloud.audit.AuditLog;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Writes the assistant's entries to the activity log. Staff entries are written as the signed-in person making the request
 * (as before). A guest has no signed-in user, so its entries go through {@link AuditLog#recordFor} with the restaurant of
 * the principal, no user id and the label "guest:channel"; the guest's subject id and text are never recorded.
 */
@Component
class AuditLogAssistantAudit implements AssistantAudit {

    private static final String SUCCESS = "SUCCESS";

    private final AuditLog audit;

    AuditLogAssistantAudit(AuditLog audit) {
        this.audit = audit;
    }

    @Override
    public void toolCalled(Principal who, String tool, String outcome) {
        record(who, "ASSISTANT_TOOL", Map.of("tool", tool, "outcome", outcome));
    }

    @Override
    public void proposed(Principal who, String kind) {
        record(who, "ASSISTANT_PROPOSE", Map.of("kind", kind));
    }

    @Override
    public void confirmed(String kind, UUID reservationId) {
        audit.record("ASSISTANT_CONFIRM", "reservation", reservationId.toString(), Map.of("kind", kind));
    }

    private void record(Principal who, String action, Map<String, String> details) {
        if (who.isGuest()) {
            audit.recordFor(who.restaurantId(), null, who.auditLabel(), action, "assistant", null, SUCCESS, details);
        } else {
            audit.record(action, "assistant", null, details);
        }
    }
}
