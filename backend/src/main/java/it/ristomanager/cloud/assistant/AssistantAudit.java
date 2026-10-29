package it.ristomanager.cloud.assistant;

import java.util.UUID;

/**
 * What the assistant records in the activity log. The text of a conversation, the model's answers and the tools' results
 * are never recorded: guests' personal data must not end up in the log. Only kinds, tool names and outcomes are, and the
 * actor is named by {@link Principal#auditLabel()} (such as "staff:WAITER" or "guest:telegram"), never by a guest's
 * subject id.
 */
public interface AssistantAudit {

    /** A tool was called. {@code outcome} is one word such as ok, error, not_allowed or proposed. */
    void toolCalled(Principal who, String tool, String outcome);

    /** A proposal was made (nothing has happened yet). Only staff can make proposals. */
    void proposed(Principal who, String kind);

    /** The person confirmed a proposal and it was carried out. Only staff confirm. */
    void confirmed(String kind, UUID reservationId);
}
