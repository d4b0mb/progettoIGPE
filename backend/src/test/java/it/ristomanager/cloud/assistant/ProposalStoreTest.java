package it.ristomanager.cloud.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.ristomanager.cloud.assistant.ProposalStore.Kind;
import it.ristomanager.cloud.assistant.ProposalStore.Proposal;
import it.ristomanager.cloud.assistant.ProposalStore.Summary;
import it.ristomanager.cloud.common.ApiException;
import it.ristomanager.cloud.user.Role;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProposalStoreTest {

    private final MovableClock clock = new MovableClock();
    private final ProposalStore store = new ProposalStore(clock);
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID restaurantA = UUID.randomUUID();
    private final UUID restaurantB = UUID.randomUUID();

    private Proposal add(UUID user, UUID restaurant) {
        return store.add(Kind.CANCEL_RESERVATION, user, restaurant, Role.OWNER, null, UUID.randomUUID(),
                new Summary("Rossi", 2, "2026-10-27", "21:00", null, ""));
    }

    private static void assertNotFound(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status().value()).isEqualTo(404);
            assertThat(e.getMessage()).isEqualTo("This proposal has expired or does not exist");
        });
    }

    @Test
    void aProposalCanBeTakenOnceByItsOwner() {
        Proposal p = add(alice, restaurantA);

        Proposal taken = store.take(p.id(), alice, restaurantA);

        assertThat(taken).isEqualTo(p);
        assertThat(taken.kind()).isEqualTo(Kind.CANCEL_RESERVATION);
        assertThat(taken.role()).isEqualTo(Role.OWNER);
        assertThat(store.size()).isZero();
        assertNotFound(() -> store.take(p.id(), alice, restaurantA));
    }

    @Test
    void anotherUserCannotTakeItAndTheOwnerStillCan() {
        Proposal p = add(alice, restaurantA);

        assertNotFound(() -> store.take(p.id(), bob, restaurantA));

        // A failed attempt by someone else must not burn the proposal.
        assertThat(store.take(p.id(), alice, restaurantA)).isEqualTo(p);
    }

    @Test
    void anotherRestaurantCannotTakeItEvenWithTheSameUserId() {
        Proposal p = add(alice, restaurantA);

        assertNotFound(() -> store.take(p.id(), alice, restaurantB));

        assertThat(store.take(p.id(), alice, restaurantA)).isEqualTo(p);
    }

    @Test
    void anUnknownIdLooksExactlyLikeAnExpiredOrForeignOne() {
        assertNotFound(() -> store.take("not-an-id", alice, restaurantA));
        assertNotFound(() -> store.take("", alice, restaurantA));
    }

    @Test
    void aProposalExpiresAfterTenMinutes() {
        Proposal p = add(alice, restaurantA);
        assertThat(p.expiresAt()).isEqualTo(p.createdAt().plus(Duration.ofMinutes(10)));

        clock.advance(Duration.ofMinutes(9).plusSeconds(59));
        Proposal stillThere = add(alice, restaurantA);
        assertThat(store.take(stillThere.id(), alice, restaurantA)).isEqualTo(stillThere);

        clock.advance(Duration.ofSeconds(1));
        assertNotFound(() -> store.take(p.id(), alice, restaurantA));
    }

    @Test
    void expiredProposalsArePurgedBySweepAndOnAdd() {
        add(alice, restaurantA);
        add(bob, restaurantA);
        assertThat(store.size()).isEqualTo(2);

        clock.advance(Duration.ofMinutes(11));
        store.sweep();
        assertThat(store.size()).isZero();

        add(alice, restaurantA);
        clock.advance(Duration.ofMinutes(11));
        add(bob, restaurantA);
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void theOldestIsDroppedWhenAUserHasMoreThanTenPending() {
        Proposal bobs = add(bob, restaurantA);
        Proposal first = add(alice, restaurantA);
        Proposal[] more = new Proposal[10];
        for (int i = 0; i < more.length; i++) {
            more[i] = add(alice, restaurantA);
        }

        // 11 were added: the first of Alice's is gone, the other ten and Bob's remain.
        assertNotFound(() -> store.take(first.id(), alice, restaurantA));
        assertThat(store.size()).isEqualTo(11);
        assertThat(store.take(more[0].id(), alice, restaurantA)).isEqualTo(more[0]);
        assertThat(store.take(more[9].id(), alice, restaurantA)).isEqualTo(more[9]);
        assertThat(store.take(bobs.id(), bob, restaurantA)).isEqualTo(bobs);
    }

    @Test
    void exactlyTenPendingAreAllKept() {
        Proposal first = add(alice, restaurantA);
        for (int i = 0; i < 9; i++) {
            add(alice, restaurantA);
        }

        assertThat(store.size()).isEqualTo(10);
        assertThat(store.take(first.id(), alice, restaurantA)).isEqualTo(first);
    }

    @Test
    void dismissForgetsOnlyTheCallersOwnProposal() {
        Proposal p = add(alice, restaurantA);

        store.dismiss(p.id(), bob, restaurantA);
        store.dismiss(p.id(), alice, restaurantB);
        assertThat(store.size()).isEqualTo(1);

        store.dismiss(p.id(), alice, restaurantA);
        assertThat(store.size()).isZero();
        assertNotFound(() -> store.take(p.id(), alice, restaurantA));
        // Dismissing something that is already gone is not an error.
        assertThatCode(() -> store.dismiss(p.id(), alice, restaurantA)).doesNotThrowAnyException();
    }

    @Test
    void idsAreLongUrlSafeAndDistinct() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            Proposal p = add(UUID.randomUUID(), restaurantA);
            // 16 random bytes, Base64 URL alphabet without padding.
            assertThat(p.id()).matches("[A-Za-z0-9_-]{22}");
            ids.add(p.id());
        }
        assertThat(ids).hasSize(200);
    }
}
