package it.ristomanager.cloud.recovery;

import it.ristomanager.cloud.common.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Slows down anyone guessing recovery answers: 5 wrong tries per email per 15 minutes, and 30 per
 * address per hour. Held in memory, so it applies per running instance and resets on restart: enough
 * for one server, to be moved into the database or a shared store before running several.
 *
 * Memory is bounded. Each map keeps its keys in order of their latest failure, so expired keys are always at the
 * front: they are dropped on every call and once a minute by {@link #sweep()}. As a hard limit, each map holds at
 * most {@link #MAX_KEYS} keys; past that the key that failed longest ago is evicted. Evicting forgets that key's
 * failures, so an attacker would need more than MAX_KEYS distinct keys inside one window to reset another key,
 * and the per-address limit makes that expensive (30 distinct emails per address per hour).
 */
@Component
public class RecoveryThrottle {

    static final int PER_EMAIL = 5;
    static final Duration EMAIL_WINDOW = Duration.ofMinutes(15);
    static final int PER_ADDRESS = 30;
    static final Duration ADDRESS_WINDOW = Duration.ofHours(1);
    /** Most keys held per map (about a few MB at this size). */
    static final int MAX_KEYS = 20_000;

    private final Clock clock;
    private final int maxKeys;
    /** Insertion order is order of the latest failure (a key is re-inserted when it fails again). */
    private final Map<String, Deque<Instant>> byEmail = new LinkedHashMap<>();
    private final Map<String, Deque<Instant>> byAddress = new LinkedHashMap<>();

    public RecoveryThrottle() {
        this(Clock.systemUTC());
    }

    RecoveryThrottle(Clock clock) {
        this(clock, MAX_KEYS);
    }

    RecoveryThrottle(Clock clock, int maxKeys) {
        this.clock = clock;
        this.maxKeys = maxKeys;
    }

    /** Throws 429 if this email or address has used up its wrong tries. */
    public synchronized void checkAllowed(String email, String address) {
        purgeExpired(clock.instant());
        if (recent(byEmail, email, EMAIL_WINDOW) >= PER_EMAIL || recent(byAddress, address, ADDRESS_WINDOW) >= PER_ADDRESS) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts. Try again in a while.");
        }
    }

    public synchronized void recordFailure(String email, String address) {
        Instant now = clock.instant();
        purgeExpired(now);
        add(byEmail, email, now, PER_EMAIL);
        add(byAddress, address, now, PER_ADDRESS);
    }

    public synchronized void clear(String email) {
        byEmail.remove(email);
    }

    /** Drops expired keys once a minute, so keys that never come back do not stay in memory. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    void sweep() {
        synchronized (this) {
            purgeExpired(clock.instant());
        }
    }

    /** How many emails are being held (tests use it). */
    synchronized int emailKeys() {
        return byEmail.size();
    }

    /** How many addresses are being held (tests use it). */
    synchronized int addressKeys() {
        return byAddress.size();
    }

    private void add(Map<String, Deque<Instant>> map, String key, Instant now, int limit) {
        // Remove and put again, so this key moves to the back: the front is always the key that failed longest ago.
        Deque<Instant> times = map.remove(key);
        if (times == null) {
            times = new ArrayDeque<>();
        }
        times.addLast(now);
        // Anything past the limit changes nothing (the key is already blocked), so do not let one key grow.
        while (times.size() > limit) {
            times.removeFirst();
        }
        map.put(key, times);
        while (map.size() > maxKeys) {
            Iterator<String> oldest = map.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
    }

    private void purgeExpired(Instant now) {
        purgeExpired(byEmail, EMAIL_WINDOW, now);
        purgeExpired(byAddress, ADDRESS_WINDOW, now);
    }

    /** Keys are in order of latest failure, so stop at the first one that is still inside its window. */
    private static void purgeExpired(Map<String, Deque<Instant>> map, Duration window, Instant now) {
        Instant cutoff = now.minus(window);
        Iterator<Deque<Instant>> it = map.values().iterator();
        while (it.hasNext()) {
            Deque<Instant> times = it.next();
            if (times.isEmpty() || times.peekLast().isBefore(cutoff)) {
                it.remove();
            } else {
                break;
            }
        }
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
