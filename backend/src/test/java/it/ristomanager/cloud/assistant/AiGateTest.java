package it.ristomanager.cloud.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.ristomanager.cloud.common.ApiException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AiGateTest {

    private final MovableClock clock = new MovableClock();
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID carol = UUID.randomUUID();

    /** A short wait so the "busy" case does not make the test slow. */
    private AiGate gate(int maxConcurrent, int perUserPerMinute) {
        return new AiGate(new AiLimits(maxConcurrent, perUserPerMinute), clock, Duration.ofMillis(50));
    }

    private static void assertStatus(Runnable action, int status, String message) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status().value()).isEqualTo(status);
            assertThat(e.getMessage()).isEqualTo(message);
        });
    }

    @Test
    void runReturnsTheWorksResult() {
        assertThat(gate(2, 10).run(alice, () -> "done")).isEqualTo("done");
    }

    @Test
    void whenAllSlotsAreTakenTheNextRequestIsTurnedAway() throws Exception {
        AiGate gate = gate(2, 100);
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> running = new ArrayList<>();
            for (UUID user : List.of(alice, bob)) {
                running.add(pool.submit(() -> gate.run(user, () -> {
                    started.countDown();
                    await(release);
                    return "finished";
                })));
            }
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            assertStatus(() -> gate.run(carol, () -> "never runs"), 503, "The assistant is busy, try again in a moment");

            release.countDown();
            for (Future<String> f : running) {
                assertThat(f.get(5, TimeUnit.SECONDS)).isEqualTo("finished");
            }
            // The slots came back.
            assertThat(gate.run(carol, () -> "now it runs")).isEqualTo("now it runs");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the test never released the work");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    void theSlotIsReleasedEvenWhenTheWorkThrows() {
        AiGate gate = gate(1, 100);

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> gate.run(alice, () -> {
                throw new IllegalStateException("the model blew up");
            })).isInstanceOf(IllegalStateException.class).hasMessage("the model blew up");
        }

        // With one slot, a leaked permit would make this a 503.
        assertThat(gate.run(alice, () -> "still works")).isEqualTo("still works");
    }

    @Test
    void aPersonOverTheirMinuteLimitGets429AndOthersAreNotAffected() {
        AiGate gate = gate(5, 3);
        for (int i = 0; i < 3; i++) {
            gate.run(alice, () -> "ok");
        }

        assertStatus(() -> gate.run(alice, () -> "no"), 429, "Too many requests, wait a moment");
        assertThat(gate.run(bob, () -> "bob is fine")).isEqualTo("bob is fine");
    }

    @Test
    void theRateLimitRecoversAsTheWindowSlides() {
        AiGate gate = gate(5, 3);
        gate.run(alice, () -> "t+0");
        clock.advance(Duration.ofSeconds(30));
        gate.run(alice, () -> "t+30");
        clock.advance(Duration.ofSeconds(10));
        gate.run(alice, () -> "t+40");

        // At t+59 the first call is still inside the window.
        clock.advance(Duration.ofSeconds(19));
        assertStatus(() -> gate.run(alice, () -> "no"), 429, "Too many requests, wait a moment");

        // At t+60 the first one has left it: exactly one call fits again.
        clock.advance(Duration.ofSeconds(1));
        assertThat(gate.run(alice, () -> "t+60")).isEqualTo("t+60");
        assertStatus(() -> gate.run(alice, () -> "no"), 429, "Too many requests, wait a moment");

        // A full quiet minute and everything is allowed again.
        clock.advance(Duration.ofSeconds(61));
        for (int i = 0; i < 3; i++) {
            assertThat(gate.run(alice, () -> "back")).isEqualTo("back");
        }
    }

    @Test
    void aRefusedRequestDoesNotCountAgainstTheLimit() {
        AiGate gate = gate(5, 2);
        gate.run(alice, () -> "1");
        gate.run(alice, () -> "2");
        for (int i = 0; i < 10; i++) {
            assertStatus(() -> gate.run(alice, () -> "no"), 429, "Too many requests, wait a moment");
        }

        // Hammering did not extend the penalty: one window after the two real calls she is free.
        clock.advance(Duration.ofSeconds(60));
        assertThat(gate.run(alice, () -> "free")).isEqualTo("free");
    }

    @Test
    void theRateMapDoesNotGrowWithoutBound() {
        AiGate gate = gate(5, 12);

        for (int i = 0; i < 3000; i++) {
            gate.run(UUID.randomUUID(), () -> "x");
            clock.advance(Duration.ofSeconds(61));
            assertThat(gate.trackedUsers()).isLessThanOrEqualTo(AiGate.SWEEP_AT);
        }
    }

    @Test
    void activeUsersAreKeptAndIdleOnesForgotten() {
        AiGate gate = gate(5, 12);
        for (int i = 0; i < AiGate.SWEEP_AT; i++) {
            gate.run(UUID.randomUUID(), () -> "x");
        }
        assertThat(gate.trackedUsers()).isEqualTo(AiGate.SWEEP_AT);

        // Everyone above went quiet; the next newcomer triggers the clean-up.
        clock.advance(Duration.ofSeconds(61));
        gate.run(alice, () -> "x");

        assertThat(gate.trackedUsers()).isEqualTo(1);
    }
}
