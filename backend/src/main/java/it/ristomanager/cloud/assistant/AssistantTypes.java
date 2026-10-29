package it.ristomanager.cloud.assistant;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/** What the assistant's endpoints exchange. */
public final class AssistantTypes {

    private AssistantTypes() {
    }

    /** The assistant added a line saying the answer touches food allergies: the screen shows a fixed, translated reminder. */
    public static final String NOTICE_ALLERGEN_CONFIRM = "ALLERGEN_CONFIRM_WITH_KITCHEN";
    /** The model's answer claimed a dish is safe for an allergy; the server replaced it. */
    public static final String NOTICE_SAFETY_CLAIM_REMOVED = "SAFETY_CLAIM_REMOVED";
    /** The model kept asking for tools and never gave an answer. */
    public static final String NOTICE_TOO_MANY_STEPS = "TOO_MANY_STEPS";
    /** The answer claimed an action that no proposal backs, so it was replaced: nothing was changed. */
    public static final String NOTICE_NOTHING_CHANGED = "NOTHING_CHANGED";
    /** A guest-facing answer promised a booking or a table; the server replaced it with "the team will confirm". */
    public static final String NOTICE_TEAM_WILL_CONFIRM = "TEAM_WILL_CONFIRM";

    /**
     * One message from the person, with the earlier turns of the conversation. Only "user" and "assistant" turns are
     * accepted from the client; anything else is dropped by the server. {@code spoken} is true when the message came from
     * voice and the reply will be read aloud: it only changes the style of the answer (short, no symbols), nothing else.
     */
    public record AssistantRequest(
            @NotBlank @Size(max = 1000) String message,
            @Size(max = 20) List<@Valid Turn> history,
            Boolean spoken) {

        public AssistantRequest(String message, List<Turn> history) {
            this(message, history, null);
        }
    }

    public record Turn(@NotBlank String role, @Size(max = 1000) String content) {
    }

    /**
     * The answer, the actions waiting for a Confirm press (usually none) and fixed notices the screen translates.
     * Nothing in the reply has been done yet: a proposal only happens when the person confirms it.
     */
    public record AssistantReply(String reply, List<ProposalView> proposals, List<String> notices) {
    }

    /**
     * An action waiting for the person's Confirm. For a cancellation it describes the reservation that would be
     * cancelled. {@code date} is YYYY-MM-DD and {@code time} HH:mm in the restaurant's time zone; {@code expiresAt} is an
     * ISO instant.
     */
    public record ProposalView(
            String id,
            String kind,
            String guestName,
            Integer partySize,
            String date,
            String time,
            @Schema(nullable = true) Integer tableNumber,
            String notes,
            String expiresAt) {
    }

    /** What the confirmed action did. */
    public record ConfirmResult(
            String kind,
            String guestName,
            Integer partySize,
            String date,
            String time,
            @Schema(nullable = true) Integer tableNumber) {
    }
}
