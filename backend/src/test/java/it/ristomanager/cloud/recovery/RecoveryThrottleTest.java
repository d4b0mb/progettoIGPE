package it.ristomanager.cloud.recovery;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.ristomanager.cloud.common.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RecoveryThrottleTest {

    /** A clock the test can move forward by hand. */
    static class MovableClock extends Clock {
        Instant now = Instant.parse("2026-10-25T10:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final MovableClock clock = new MovableClock();
    private final RecoveryThrottle throttle = new RecoveryThrottle(clock);

    @Test
    void allowsFewWrongTriesButBlocksTheSixth() {
        for (int i = 0; i < 5; i++) {
            throttle.checkAllowed("owner@example.com", "10.0.0.1");
            throttle.recordFailure("owner@example.com", "10.0.0.1");
        }
        assertThatThrownBy(() -> throttle.checkAllowed("owner@example.com", "10.0.0.1"))
                .isInstanceOfSatisfying(ApiException.class, e -> org.assertj.core.api.Assertions.assertThat(e.status().value()).isEqualTo(429));
    }

    @Test
    void theBlockEndsOnceTheWindowHasPassed() {
        for (int i = 0; i < 5; i++) {
            throttle.recordFailure("owner@example.com", "10.0.0.1");
        }
        clock.advance(Duration.ofMinutes(16));
        assertThatCode(() -> throttle.checkAllowed("owner@example.com", "10.0.0.1")).doesNotThrowAnyException();
    }

    @Test
    void oneEmailBeingGuessedDoesNotBlockAnother() {
        for (int i = 0; i < 5; i++) {
            throttle.recordFailure("victim@example.com", "10.0.0." + i);
        }
        assertThatCode(() -> throttle.checkAllowed("someone-else@example.com", "10.0.0.99")).doesNotThrowAnyException();
    }

    @Test
    void oneAddressTryingManyEmailsIsStoppedEventually() {
        for (int i = 0; i < 30; i++) {
            throttle.recordFailure("victim" + i + "@example.com", "10.9.9.9");
        }
        assertThatThrownBy(() -> throttle.checkAllowed("brand-new@example.com", "10.9.9.9")).isInstanceOf(ApiException.class);
    }

    @Test
    void aSuccessfulRecoveryClearsTheEmailsFailures() {
        for (int i = 0; i < 4; i++) {
            throttle.recordFailure("owner@example.com", "10.0.0.1");
        }
        throttle.clear("owner@example.com");
        throttle.recordFailure("owner@example.com", "10.0.0.1");
        assertThatCode(() -> throttle.checkAllowed("owner@example.com", "10.0.0.2")).doesNotThrowAnyException();
    }
}
