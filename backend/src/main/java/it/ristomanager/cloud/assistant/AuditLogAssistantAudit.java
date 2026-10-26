package it.ristomanager.cloud.assistant;

import it.ristomanager.cloud.audit.AuditLog;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Writes the assistant's entries to the activity log, as the signed-in person making the request. */
@Component
class AuditLogAssistantAudit implements AssistantAudit {

    private final AuditLog audit;

    AuditLogAssistantAudit(AuditLog audit) {
        this.audit = audit;
    }

    @Override
    public void toolCalled(String tool, String outcome) {
        audit.record("ASSISTANT_TOOL", "assistant", null, Map.of("tool", tool, "outcome", outcome));
    }

    @Override
    public void proposed(String kind) {
        audit.record("ASSISTANT_PROPOSE", "assistant", null, Map.of("kind", kind));
    }

    @Override
    public void confirmed(String kind, UUID reservationId) {
        audit.record("ASSISTANT_CONFIRM", "reservation", reservationId.toString(), Map.of("kind", kind));
    }
}
