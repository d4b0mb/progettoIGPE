package it.ristomanager.cloud.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class UnscopedFailureLimiterTest {

    /** A clock the tests move by hand. It starts exactly on a minute, so windows are easy to reason about. */
    static class TestClock extends Clock {
        Instant now = Instant.parse("2026-10-26T17:40:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final TestClock clock = new TestClock();

    @Test
    void oneAddressGetsItsShareAndNoMoreInAMinute() {
        UnscopedFailureLimiter limiter = new UnscopedFailureLimiter(3, 100, clock);

        assertThat(limiter.tryAdmit("10.0.0.1")).isTrue();
        assertThat(limiter.tryAdmit("10.0.0.1")).isTrue();
        assertThat(limiter.tryAdmit("10.0.0.1")).isTrue();
        assertThat(limiter.tryAdmit("10.0.0.1")).isFalse();
        assertThat(limiter.tryAdmit("10.0.0.1")).isFalse();
        // Another address is not affected by the first one's flood.
        assertThat(limiter.tryAdmit("10.0.0.2")).isTrue();
    }

    @Test
    void allAddressesTogetherShareTheOverallLimit() {
        UnscopedFailureLimiter limiter = new UnscopedFailureLimiter(10, 5, clock);

        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAdmit("10.0.0." + i)).isTrue();
        }
        assertThat(limiter.tryAdmit("10.0.0.99")).isFalse();
        assertThat(limiter.tryAdmit("10.0.0.0")).isFalse();
    }

    @Test
    void theWindowResetsAfterAMinute() {
        UnscopedFailureLimiter limiter = new UnscopedFailureLimiter(2, 100, clock);
        assertThat(limiter.tryAdmit("a")).isTrue();
        assertThat(limiter.tryAdmit("a")).isTrue();
        assertThat(limiter.tryAdmit("a")).isFalse();

        clock.advance(Duration.ofSeconds(59));
        assertThat(limiter.tryAdmit("a")).as("still the same minute").isFalse();

        clock.advance(Duration.ofSeconds(1));
        assertThat(limiter.tryAdmit("a")).as("a new minute").isTrue();
        assertThat(limiter.tryAdmit("a")).isTrue();
        assertThat(limiter.tryAdmit("a")).isFalse();
    }

    @Test
    void theDroppedCountIsHandedOutOnceAfterTheMinuteEnds() {
        UnscopedFailureLimiter limiter = new UnscopedFailureLimiter(1, 100, clock);
        limiter.tryAdmit("a");
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAdmit("a")).isFalse();
        }
        assertThat(limiter.takeSummary()).as("the minute is not over yet").isZero();

        clock.advance(Duration.ofMinutes(1));
        assertThat(limiter.takeSummary()).isEqualTo(5);
        assertThat(limiter.takeSummary()).as("only once").isZero();
    }

    @Test
    void aMinuteWithNothingDroppedGivesNoSummary() {
        UnscopedFailureLimiter limiter = new UnscopedFailureLimiter(5, 100, clock);
        limiter.tryAdmit("a");
        limiter.tryAdmit("b");
        clock.advance(Duration.ofMinutes(1));
        assertThat(limiter.takeSummary()).isZero();
    }

    @Test
    void severalFinishedMinutesAreSummedIntoOneCount() {
        UnscopedFailureLimiter limiter = new UnscopedFailureLimiter(1, 100, clock);
        limiter.tryAdmit("a");
        limiter.tryAdmit("a");
        limiter.tryAdmit("a");
        clock.advance(Duration.ofMinutes(1));
        limiter.tryAdmit("a");
        limiter.tryAdmit("a");
        clock.advance(Duration.ofMinutes(1));

        assertThat(limiter.takeSummary()).isEqualTo(3);
    }

    @Test
    void anAdmittedCallAfterTheMinuteStillCollectsTheEarlierCount() {
        UnscopedFailureLimiter limiter = new UnscopedFailureLimiter(1, 100, clock);
        limiter.tryAdmit("a");
        limiter.tryAdmit("a");
        clock.advance(Duration.ofMinutes(2));
        assertThat(limiter.tryAdmit("a")).isTrue();
        assertThat(limiter.takeSummary()).isEqualTo(1);
    }

    @Test
    void memoryStaysBoundedWhateverTheNumberOfAddresses() {
        UnscopedFailureLimiter limiter = new UnscopedFailureLimiter(20, 50, clock);
        for (int i = 0; i < 10_000; i++) {
            limiter.tryAdmit("addr-" + i);
        }
        assertThat(limiter.trackedAddresses()).isLessThanOrEqualTo(50);

        clock.advance(Duration.ofMinutes(1));
        limiter.tryAdmit("addr-new");
        assertThat(limiter.trackedAddresses()).as("old minute forgotten").isEqualTo(1);
        assertThat(limiter.takeSummary()).isEqualTo(10_000 - 50);
    }

    @Test
    void oddInputNeverThrowsAndSettingsAreClamped() {
        UnscopedFailureLimiter limiter = new UnscopedFailureLimiter(0, -5, clock);

        assertThatCode(() -> {
            limiter.tryAdmit(null);
            limiter.tryAdmit("");
            limiter.tryAdmit("   ");
            limiter.tryAdmit("x".repeat(100_000));
            limiter.takeSummary();
        }).doesNotThrowAnyException();

        // Zero or negative settings still allow one entry per minute rather than none or an error.
        UnscopedFailureLimiter strict = new UnscopedFailureLimiter(0, 0, clock);
        assertThat(strict.tryAdmit("a")).isTrue();
        assertThat(strict.tryAdmit("a")).isFalse();
    }

    @Test
    void aBrokenClockNeverBreaksTheLog() {
        Clock broken = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                throw new IllegalStateException("clock failure");
            }
        };
        UnscopedFailureLimiter limiter = new UnscopedFailureLimiter(1, 1, broken);

        assertThat(limiter.tryAdmit("a")).as("fails open: the entry is written").isTrue();
        assertThat(limiter.takeSummary()).isZero();
    }
}
