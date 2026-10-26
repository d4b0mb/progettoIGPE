package it.ristomanager.cloud.assistant;

import it.ristomanager.cloud.assistant.AssistantData.ReservationDraft;
import it.ristomanager.cloud.common.ApiException;
import it.ristomanager.cloud.user.Role;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Holds what the assistant has proposed until the person presses Confirm (or it expires after 10 minutes).
 *
 * This is the other half of the central safety property: the model can only PUT a proposal here. Nothing it writes can
 * take one out and run it; that needs a separate, authenticated request by the same person for the same restaurant,
 * carrying the proposal's id. The id is 128 random bits from {@link SecureRandom}, so it cannot be guessed, and it can
 * be used once.
 *
 * In memory and for a single application instance only: a restart forgets every pending proposal (the person simply
 * asks again), and a second instance would not see the first one's proposals. Move it to the database or a shared
 * store before running several instances.
 */
@Component
public class ProposalStore {

    public enum Kind {
        CREATE_RESERVATION, CANCEL_RESERVATION
    }

    /** What the card shows. Date and time are in the restaurant's time zone. */
    public record Summary(String guestName, int partySize, String date, String time, Integer tableNumber, String notes) {
    }

    /**
     * One pending action. {@code draft} is set for a create, {@code reservationId} for a cancel. {@code role} is the role
     * the person had when the proposal was made.
     */
    public record Proposal(String id, Kind kind, UUID userId, UUID restaurantId, Role role, Instant createdAt,
                           Instant expiresAt, ReservationDraft draft, UUID reservationId, Summary summary) {
    }

    static final Duration LIFETIME = Duration.ofMinutes(10);
    static final int MAX_PENDING_PER_USER = 10;
    static final String NOT_FOUND = "This proposal has expired or does not exist";

    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    /** Insertion order is age order, so the first entry of a user is that user's oldest. */
    private final Map<String, Proposal> pending = new LinkedHashMap<>();

    public ProposalStore() {
        this(Clock.systemUTC());
    }

    ProposalStore(Clock clock) {
        this.clock = clock;
    }

    /** Stores a new proposal. If the person now has more than 10 pending, the oldest of theirs is dropped. */
    public synchronized Proposal add(Kind kind, UUID userId, UUID restaurantId, Role role, ReservationDraft draft,
                                     UUID reservationId, Summary summary) {
        Instant now = clock.instant();
        purgeExpired(now);
        Proposal proposal = new Proposal(newId(), kind, userId, restaurantId, role, now, now.plus(LIFETIME),
                draft, reservationId, summary);
        pending.put(proposal.id(), proposal);
        long mine = pending.values().stream().filter(p -> p.userId().equals(userId)).count();
        for (Iterator<Proposal> it = pending.values().iterator(); mine > MAX_PENDING_PER_USER && it.hasNext(); ) {
            if (it.next().userId().equals(userId)) {
                it.remove();
                mine--;
            }
        }
        return proposal;
    }

    /**
     * Removes and returns the proposal, once, if it exists, has not expired and belongs to this person in this
     * restaurant. Every other case is the same 404, so nobody can tell "never existed" from "someone else's".
     */
    public synchronized Proposal take(String id, UUID userId, UUID restaurantId) {
        Proposal proposal = pending.get(id);
        if (proposal == null || expired(proposal, clock.instant())
                || !proposal.userId().equals(userId) || !proposal.restaurantId().equals(restaurantId)) {
            throw ApiException.notFound(NOT_FOUND);
        }
        pending.remove(id);
        return proposal;
    }

    /** Forgets a proposal of this person. Quietly does nothing when there is none, so the person can always "dismiss". */
    public synchronized void dismiss(String id, UUID userId, UUID restaurantId) {
        Proposal proposal = pending.get(id);
        if (proposal != null && proposal.userId().equals(userId) && proposal.restaurantId().equals(restaurantId)) {
            pending.remove(id);
        }
    }

    /** Removes expired proposals once a minute, so forgotten ones do not pile up. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    void sweep() {
        synchronized (this) {
            purgeExpired(clock.instant());
        }
    }

    /** How many proposals are held (tests use it). */
    synchronized int size() {
        return pending.size();
    }

    private void purgeExpired(Instant now) {
        pending.values().removeIf(p -> expired(p, now));
    }

    private static boolean expired(Proposal p, Instant now) {
        return !now.isBefore(p.expiresAt());
    }

    private String newId() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
