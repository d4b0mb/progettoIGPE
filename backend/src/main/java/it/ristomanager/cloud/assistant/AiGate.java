package it.ristomanager.cloud.assistant;

import it.ristomanager.cloud.common.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The door every model-backed request goes through. It protects the single local model from being flooded:
 * one person may start only so many requests a minute, and only so many model calls run at once; a request that
 * cannot get a slot within two seconds is turned away with a clear message instead of waiting.
 *
 * Held in memory, so the limits apply per running instance (the application runs as one instance).
 *
 * {@code @Primary}: this is the assistant's gate. Voice has its own instance (bean "voiceGate", see VoiceConfig) with
 * its own limits, so by-type injection of an AiGate keeps meaning this one.
 */
@Service
@Primary
public class AiGate {

    static final Duration WINDOW = Duration.ofSeconds(60);
    static final Duration DEFAULT_WAIT = Duration.ofSeconds(2);
    /** When the map of recent callers reaches this size, entries whose window is empty are swept out. */
    static final int SWEEP_AT = 256;

    private final Semaphore slots;
    private final int perUserPerMinute;
    private final Clock clock;
    private final Duration wait;
    private final Map<UUID, Deque<Instant>> recent = new HashMap<>();

    @Autowired
    public AiGate(AiLimits limits) {
        this(limits, Clock.systemUTC(), DEFAULT_WAIT);
    }

    AiGate(AiLimits limits, Clock clock, Duration wait) {
        this.slots = new Semaphore(Math.max(1, limits.maxConcurrent()));
        this.perUserPerMinute = Math.max(1, limits.perUserPerMinute());
        this.clock = clock;
        this.wait = wait;
    }

    /**
     * Runs the work if this person is within their rate and a model slot is free, and always frees the slot afterwards.
     *
     * @throws ApiException 429 when the person started too many requests in the last minute, 503 when no slot freed up in time
     */
    public <T> T run(UUID userId, Supplier<T> work) {
        checkRate(userId);
        boolean acquired;
        try {
            acquired = slots.tryAcquire(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "The assistant is busy, try again in a moment");
        }
        try {
            return work.get();
        } finally {
            slots.release();
        }
    }

    private synchronized void checkRate(UUID userId) {
        Instant now = clock.instant();
        Instant cutoff = now.minus(WINDOW);
        Deque<Instant> times = recent.get(userId);
        if (times == null) {
            if (recent.size() >= SWEEP_AT) {
                sweep(cutoff);
            }
            times = new ArrayDeque<>();
            recent.put(userId, times);
        }
        while (!times.isEmpty() && !times.peekFirst().isAfter(cutoff)) {
            times.removeFirst();
        }
        if (times.size() >= perUserPerMinute) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests, wait a moment");
        }
        times.addLast(now);
    }

    /** Drops every person whose last call is outside the window, so the map only holds people active in the last minute. */
    private void sweep(Instant cutoff) {
        Iterator<Deque<Instant>> it = recent.values().iterator();
        while (it.hasNext()) {
            Deque<Instant> times = it.next();
            if (times.isEmpty() || !times.peekLast().isAfter(cutoff)) {
                it.remove();
            }
        }
    }

    /** How many people are currently tracked (tests use it to check the map stays small). */
    synchronized int trackedUsers() {
        return recent.size();
    }
}
