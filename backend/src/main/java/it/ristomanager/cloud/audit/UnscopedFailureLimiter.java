package it.ristomanager.cloud.audit;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Keeps anonymous clients from growing the activity log without bound. It decides whether an entry with no
 * restaurant (a failed sign-in or recovery for an unknown email) may be written: at most N per client address
 * and M in total per minute. The rest are counted, and the count of a finished minute is handed out once, for a
 * single summary entry.
 *
 * Fixed one-minute windows: simple, and the memory is bounded because an address only gets an entry when one
 * of its events is admitted, so there are never more entries than the overall limit, and everything is dropped
 * when the minute ends. A burst across a minute boundary can therefore be up to twice the limit. Held in
 * memory, so it applies per running instance and resets on restart (like RecoveryThrottle and AiGate).
 *
 * There is deliberately no lockout: a client over the limit is not refused, only its log entries are not kept.
 * Nothing here throws.
 */
@Component
public class UnscopedFailureLimiter {

    static final long WINDOW_MILLIS = 60_000L;
    /** Upper bound on the overall limit, so a mistaken setting cannot make the map unbounded. */
    static final int MAX_OVERALL = 100_000;
    static final String UNKNOWN_ADDRESS = "unknown";
    static final int MAX_ADDRESS_LENGTH = 64;

    private final int perAddress;
    private final int overall;
    private final Clock clock;

    private long window = Long.MIN_VALUE;
    private int admittedInWindow;
    private final Map<String, Integer> admittedByAddress = new HashMap<>();
    private long droppedInWindow;
    /** Dropped counts of finished minutes, not yet handed out. */
    private long pendingSummary;

    @Autowired
    public UnscopedFailureLimiter(AuditProperties props, ObjectProvider<Clock> clock) {
        this(props.unscopedPerAddressPerMinute(), props.unscopedPerMinute(), clock.getIfAvailable(Clock::systemUTC));
    }

    UnscopedFailureLimiter(int perAddress, int overall, Clock clock) {
        this.overall = Math.min(MAX_OVERALL, Math.max(1, overall));
        this.perAddress = Math.max(1, perAddress);
        this.clock = clock;
    }

    /** True if an entry from this address may be written now; false if it is over a limit (and is counted as dropped). */
    public synchronized boolean tryAdmit(String address) {
        try {
            roll();
            String key = key(address);
            int mine = admittedByAddress.getOrDefault(key, 0);
            if (admittedInWindow >= overall || mine >= perAddress) {
                droppedInWindow++;
                return false;
            }
            admittedByAddress.put(key, mine + 1);
            admittedInWindow++;
            return true;
        } catch (RuntimeException e) {
            return true; // the log must never break because of its own limiter
        }
    }

    /**
     * How many entries were dropped in minutes that have finished, since the last call; zero when none.
     * The caller writes one summary entry for a non-zero answer.
     */
    public synchronized long takeSummary() {
        try {
            roll();
            long n = pendingSummary;
            pendingSummary = 0;
            return n;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** How many addresses are tracked right now (tests use it to check the memory stays bounded). */
    synchronized int trackedAddresses() {
        return admittedByAddress.size();
    }

    private void roll() {
        long now = Math.floorDiv(clock.millis(), WINDOW_MILLIS);
        if (now != window) {
            pendingSummary += droppedInWindow;
            droppedInWindow = 0;
            admittedInWindow = 0;
            admittedByAddress.clear();
            window = now;
        }
    }

    private static String key(String address) {
        if (address == null || address.isBlank()) {
            return UNKNOWN_ADDRESS;
        }
        return address.length() <= MAX_ADDRESS_LENGTH ? address : address.substring(0, MAX_ADDRESS_LENGTH);
    }
}
