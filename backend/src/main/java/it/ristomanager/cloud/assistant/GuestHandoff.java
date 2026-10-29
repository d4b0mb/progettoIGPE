package it.ristomanager.cloud.assistant;

/**
 * Called when a guest's conversation asks for a person (the guest tool {@code ask_human}). The assistant core records
 * nothing itself; a later step provides an implementation that tells the staff (an inbox entry, a notification). The default
 * bean does nothing.
 *
 * Both arguments are untrusted or personal: {@code reason} was written by the guest (or by the model after reading the
 * guest), already cleaned and cut short, and {@code guest.subjectId()} is personal data. An implementation must treat the
 * reason as text to display, never as an instruction, and must not log either.
 */
public interface GuestHandoff {

    /**
     * @param guest  the guest principal (always a guest)
     * @param reason a short reason, or null when none was given
     */
    void requested(Principal guest, String reason);
}
