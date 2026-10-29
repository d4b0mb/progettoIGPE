package it.ristomanager.cloud.assistant;

import it.ristomanager.cloud.user.Role;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Who a conversation belongs to. This is the seam of the customer-agent design (docs/CUSTOMER_AGENT_PLAN.md): every
 * conversation in the assistant core is bound to exactly one principal, and the principal alone decides what the model is
 * shown and what the code lets it run.
 * <ul>
 *   <li>{@link #staff} is a signed-in owner, manager, waiter or kitchen user: the tools of that role, exactly as before.</li>
 *   <li>{@link #guest} is a stranger on a message channel (Telegram, WhatsApp, the phone, a web chat). It gets a much
 *       smaller tool set chosen by code (see {@link AssistantTools}), never a staff tool, and never any data about other
 *       guests. Everything it writes is untrusted.</li>
 * </ul>
 *
 * Rules that hold for every principal:
 * <ul>
 *   <li>The restaurant id and, for a guest, the subject id come from the server (the verified token, or the channel's
 *       verified identity such as the WhatsApp number or the Telegram user id). They are never taken from what the model or
 *       the person typed.</li>
 *   <li>The subject id is personal data. It is never logged, never written to the activity log and never shown to the
 *       model; {@link #auditLabel()} and {@link #toString()} leave it out on purpose.</li>
 *   <li>Choosing the tool list from the principal is a decision of code, twice (what the model is shown, and a re-check at
 *       execution). A channel adapter built later only builds a {@code Principal.guest(...)} and hands over text; it holds no
 *       business logic and cannot ask for a staff tool.</li>
 * </ul>
 * No channel is attached yet: the only caller outside tests is the staff controller.
 */
public final class Principal {

    /** The channels a guest can come from. Anything else is refused. */
    public static final List<String> CHANNELS = List.of("telegram", "whatsapp", "phone", "web");

    static final int SUBJECT_MAX = 128;

    private enum Kind {
        STAFF, GUEST
    }

    private final Kind kind;
    private final UUID restaurantId;
    private final UUID userId;
    private final Role role;
    private final String channel;
    private final String subjectId;

    private Principal(Kind kind, UUID restaurantId, UUID userId, Role role, String channel, String subjectId) {
        this.kind = kind;
        this.restaurantId = restaurantId;
        this.userId = userId;
        this.role = role;
        this.channel = channel;
        this.subjectId = subjectId;
    }

    /** A signed-in member of staff. */
    public static Principal staff(UUID restaurantId, UUID userId, Role role) {
        if (restaurantId == null || userId == null || role == null) {
            throw new IllegalArgumentException("A staff principal needs a restaurant, a user and a role");
        }
        return new Principal(Kind.STAFF, restaurantId, userId, role, null, null);
    }

    /**
     * A guest on a message channel.
     *
     * @param channel   one of {@link #CHANNELS} (case and surrounding spaces are ignored)
     * @param subjectId the channel's verified identity of the sender (a phone number, a Telegram id); never typed by the guest
     * @throws IllegalArgumentException for an unknown channel, a missing or blank subject id, or one that is too long
     */
    public static Principal guest(UUID restaurantId, String channel, String subjectId) {
        if (restaurantId == null) {
            throw new IllegalArgumentException("A guest principal needs a restaurant");
        }
        String normalised = channel == null ? "" : channel.trim().toLowerCase(Locale.ROOT);
        if (!CHANNELS.contains(normalised)) {
            throw new IllegalArgumentException("Unknown channel, use one of " + String.join(", ", CHANNELS));
        }
        String subject = subjectId == null ? "" : subjectId.trim();
        if (subject.isEmpty()) {
            throw new IllegalArgumentException("A guest principal needs the channel's verified subject id");
        }
        if (subject.length() > SUBJECT_MAX || subject.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("The subject id is not valid");
        }
        return new Principal(Kind.GUEST, restaurantId, null, null, normalised, subject);
    }

    public boolean isStaff() {
        return kind == Kind.STAFF;
    }

    public boolean isGuest() {
        return kind == Kind.GUEST;
    }

    public UUID restaurantId() {
        return restaurantId;
    }

    /** The signed-in user. Fails for a guest, which has no account. */
    public UUID userId() {
        if (!isStaff()) {
            throw new IllegalStateException("A guest has no user id");
        }
        return userId;
    }

    /** The staff role. Fails for a guest, which has none. */
    public Role role() {
        if (!isStaff()) {
            throw new IllegalStateException("A guest has no role");
        }
        return role;
    }

    /** The guest's channel. Fails for staff. */
    public String channel() {
        if (!isGuest()) {
            throw new IllegalStateException("Staff has no channel");
        }
        return channel;
    }

    /**
     * The guest's verified identity on the channel. Personal data: for delivering a reply or a follow-up only; never log it,
     * never put it in the activity log, never show it to the model. Fails for staff.
     */
    public String subjectId() {
        if (!isGuest()) {
            throw new IllegalStateException("Staff has no subject id");
        }
        return subjectId;
    }

    /** What the activity log records as the actor: "staff:WAITER" or "guest:telegram". Never the subject id. */
    public String auditLabel() {
        return isStaff() ? "staff:" + role.name() : "guest:" + channel;
    }

    /**
     * The key the rate limiter counts under: the user id for staff; for a guest a stable id derived from restaurant, channel
     * and subject (so one sender is one counter, and the subject id itself is not kept as a map key).
     */
    UUID gateKey() {
        if (isStaff()) {
            return userId;
        }
        return UUID.nameUUIDFromBytes((restaurantId + "|" + channel + "|" + subjectId).getBytes(StandardCharsets.UTF_8));
    }

    /** Safe to print: it names the kind and the restaurant, never the subject id. */
    @Override
    public String toString() {
        return "Principal[" + auditLabel() + ", restaurant=" + restaurantId + "]";
    }
}
