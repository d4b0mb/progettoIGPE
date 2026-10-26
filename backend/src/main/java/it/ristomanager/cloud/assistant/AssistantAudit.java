package it.ristomanager.cloud.assistant;

import java.util.UUID;

/**
 * What the assistant records in the activity log. The text of a conversation, the model's answers and the tools' results
 * are never recorded: guests' personal data must not end up in the log. Only kinds, tool names and outcomes are.
 */
public interface AssistantAudit {

    /** A tool was called. {@code outcome} is one word such as ok, error, not_allowed or proposed. */
    void toolCalled(String tool, String outcome);

    /** A proposal was made (nothing has happened yet). */
    void proposed(String kind);

    /** The person confirmed a proposal and it was carried out. */
    void confirmed(String kind, UUID reservationId);
}
