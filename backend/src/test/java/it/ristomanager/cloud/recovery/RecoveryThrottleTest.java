package it.ristomanager.cloud.recovery;

import static org.assertj.core.api.Assertions.assertThat;
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

    // ---- memory stays bounded

    @Test
    void manyDistinctKeysCannotGrowTheMapsPastTheCap() {
        for (int i = 0; i < 100_000; i++) {
            throttle.recordFailure("nobody-" + i + "@example.com", "addr-" + i);
        }
        assertThat(throttle.emailKeys()).isLessThanOrEqualTo(RecoveryThrottle.MAX_KEYS);
        assertThat(throttle.addressKeys()).isLessThanOrEqualTo(RecoveryThrottle.MAX_KEYS);
        // The cap evicts the oldest, so the newest keys are still remembered.
        for (int i = 0; i < 5; i++) {
            throttle.recordFailure("nobody-99999@example.com", "addr-99999");
        }
        assertThatThrownBy(() -> throttle.checkAllowed("nobody-99999@example.com", "fresh-address")).isInstanceOf(ApiException.class);
    }

    @Test
    void theCapEvictsTheKeyThatFailedLongestAgo() {
        RecoveryThrottle small = new RecoveryThrottle(clock, 3);
        small.recordFailure("a@example.com", "10.0.0.1");
        small.recordFailure("b@example.com", "10.0.0.2");
        small.recordFailure("c@example.com", "10.0.0.3");
        small.recordFailure("a@example.com", "10.0.0.1"); // a is now the most recent, b the oldest
        small.recordFailure("d@example.com", "10.0.0.4"); // over the cap: b goes
        assertThat(small.emailKeys()).isEqualTo(3);
        assertThat(small.addressKeys()).isEqualTo(3);
        // b is forgotten: five more failures are needed to block it, while a (2 recorded) needs only three more.
        for (int i = 0; i < 3; i++) {
            small.recordFailure("a@example.com", "10.1.0." + i);
        }
        assertThatThrownBy(() -> small.checkAllowed("a@example.com", "10.2.0.1")).isInstanceOf(ApiException.class);
        for (int i = 0; i < 3; i++) {
            small.recordFailure("b@example.com", "10.3.0." + i);
        }
        assertThatCode(() -> small.checkAllowed("b@example.com", "10.4.0.1")).doesNotThrowAnyException();
    }

    @Test
    void expiredKeysDisappearWithoutBeingAskedAgain() {
        for (int i = 0; i < 1_000; i++) {
            throttle.recordFailure("nobody-" + i + "@example.com", "addr-" + i);
        }
        assertThat(throttle.emailKeys()).isEqualTo(1_000);

        clock.advance(RecoveryThrottle.EMAIL_WINDOW.plusSeconds(1));
        throttle.sweep();
        assertThat(throttle.emailKeys()).isZero();
        // Addresses are held for an hour.
        assertThat(throttle.addressKeys()).isEqualTo(1_000);

        clock.advance(RecoveryThrottle.ADDRESS_WINDOW);
        throttle.sweep();
        assertThat(throttle.addressKeys()).isZero();
    }

    @Test
    void newTrafficAlsoPurgesExpiredKeys() {
        throttle.recordFailure("old@example.com", "10.0.0.1");
        clock.advance(Duration.ofHours(2));
        throttle.recordFailure("new@example.com", "10.0.0.2");
        assertThat(throttle.emailKeys()).isEqualTo(1);
        assertThat(throttle.addressKeys()).isEqualTo(1);
    }

    @Test
    void aKeyThatKeepsFailingIsNotPurgedBeforeItsWindowEnds() {
        throttle.recordFailure("owner@example.com", "10.0.0.1");
        clock.advance(Duration.ofMinutes(10));
        throttle.recordFailure("other@example.com", "10.0.0.2");
        throttle.recordFailure("owner@example.com", "10.0.0.1"); // moves owner to the back
        clock.advance(Duration.ofMinutes(6)); // the first failure of owner is now 16 minutes old, the second 6
        throttle.sweep();
        assertThat(throttle.emailKeys()).isEqualTo(2);
        clock.advance(Duration.ofMinutes(10)); // the second failure of owner is 16 minutes old, other's is 16 too
        throttle.sweep();
        assertThat(throttle.emailKeys()).isZero();
    }

    // ---- the limits themselves are unchanged

    @Test
    void failuresInsideTheWindowStillCountAfterOtherKeysExpired() {
        for (int i = 0; i < 4; i++) {
            throttle.recordFailure("owner@example.com", "10.0.0.1");
            clock.advance(Duration.ofMinutes(2));
        }
        throttle.recordFailure("stranger@example.com", "10.0.0.9");
        throttle.sweep();
        throttle.recordFailure("owner@example.com", "10.0.0.1");
        assertThatThrownBy(() -> throttle.checkAllowed("owner@example.com", "10.0.0.5")).isInstanceOf(ApiException.class);
    }

    @Test
    void theAddressLimitIsThirtyPerHourAndEndsAfterTheHour() {
        for (int i = 0; i < 29; i++) {
            throttle.recordFailure("victim" + i + "@example.com", "10.9.9.9");
        }
        assertThatCode(() -> throttle.checkAllowed("brand-new@example.com", "10.9.9.9")).doesNotThrowAnyException();
        throttle.recordFailure("victim29@example.com", "10.9.9.9");
        assertThatThrownBy(() -> throttle.checkAllowed("brand-new@example.com", "10.9.9.9")).isInstanceOf(ApiException.class);
        clock.advance(Duration.ofHours(1).plusSeconds(1));
        assertThatCode(() -> throttle.checkAllowed("brand-new@example.com", "10.9.9.9")).doesNotThrowAnyException();
    }
}
