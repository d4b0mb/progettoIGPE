package it.ristomanager.cloud.recovery;

import it.ristomanager.cloud.common.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Slows down anyone guessing recovery answers: 5 wrong tries per email per 15 minutes, and 30 per
 * address per hour. Held in memory, so it applies per running instance and resets on restart: enough
 * for one server, to be moved into the database or a shared store before running several.
 */
@Component
public class RecoveryThrottle {

    static final int PER_EMAIL = 5;
    static final Duration EMAIL_WINDOW = Duration.ofMinutes(15);
    static final int PER_ADDRESS = 30;
    static final Duration ADDRESS_WINDOW = Duration.ofHours(1);

    private final Clock clock;
    private final Map<String, Deque<Instant>> byEmail = new HashMap<>();
    private final Map<String, Deque<Instant>> byAddress = new HashMap<>();

    public RecoveryThrottle() {
        this(Clock.systemUTC());
    }

    RecoveryThrottle(Clock clock) {
        this.clock = clock;
    }

    /** Throws 429 if this email or address has used up its wrong tries. */
    public synchronized void checkAllowed(String email, String address) {
        if (recent(byEmail, email, EMAIL_WINDOW) >= PER_EMAIL || recent(byAddress, address, ADDRESS_WINDOW) >= PER_ADDRESS) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts. Try again in a while.");
        }
    }

    public synchronized void recordFailure(String email, String address) {
        Instant now = clock.instant();
        byEmail.computeIfAbsent(email, k -> new ArrayDeque<>()).addLast(now);
        byAddress.computeIfAbsent(address, k -> new ArrayDeque<>()).addLast(now);
    }

    public synchronized void clear(String email) {
        byEmail.remove(email);
    }

    private int recent(Map<String, Deque<Instant>> map, String key, Duration window) {
        Deque<Instant> times = map.get(key);
        if (times == null) {
            return 0;
        }
        Instant cutoff = clock.instant().minus(window);
        while (!times.isEmpty() && times.peekFirst().isBefore(cutoff)) {
            times.removeFirst();
        }
        if (times.isEmpty()) {
            map.remove(key);
            return 0;
        }
        return times.size();
    }
}
