package it.ristomanager.cloud.order;

import it.ristomanager.cloud.order.OrderTypes.TicketView;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Pushes ticket changes to the kitchen and floor screens of the same restaurant, and
 * only that restaurant. This version keeps subscribers in memory, so it serves a
 * single API instance. Running several instances needs a shared channel (PostgreSQL
 * LISTEN/NOTIFY or Redis) behind this same class; see ADR 0005.
 */
@Component
public class TicketEvents {

    private static final Logger log = LoggerFactory.getLogger(TicketEvents.class);

    /** A stream ends after this long and the client reconnects with a fresh login token. */
    static final Duration STREAM_LIFETIME = Duration.ofMinutes(10);

    private final Map<UUID, Set<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID restaurantId) {
        SseEmitter emitter = new SseEmitter(STREAM_LIFETIME.toMillis());
        Set<SseEmitter> set = subscribers.computeIfAbsent(restaurantId, k -> ConcurrentHashMap.newKeySet());
        set.add(emitter);
        Runnable remove = () -> {
            set.remove(emitter);
            if (set.isEmpty()) {
                subscribers.remove(restaurantId, set);
            }
        };
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        try {
            // Tells the client the stream is live; it should load the current queue after this.
            emitter.send(SseEmitter.event().name("ready").data("ok"));
        } catch (IOException e) {
            remove.run();
        }
        return emitter;
    }

    /** Sends after the database transaction commits, so screens never show something that was rolled back. */
    public void publish(UUID restaurantId, String type, TicketView ticket) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send(restaurantId, type, ticket);
                }
            });
        } else {
            send(restaurantId, type, ticket);
        }
    }

    private void send(UUID restaurantId, String type, TicketView ticket) {
        Set<SseEmitter> set = subscribers.get(restaurantId);
        if (set == null) {
            return;
        }
        for (SseEmitter emitter : set) {
            try {
                emitter.send(SseEmitter.event().name(type).data(ticket, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                set.remove(emitter);
                log.debug("Dropped a closed stream", e);
            }
        }
    }

    /** Keeps idle connections open through proxies and detects dead ones. */
    @Scheduled(fixedDelay = 25_000)
    void heartbeat() {
        subscribers.values().forEach(set -> set.forEach(emitter -> {
            try {
                emitter.send(SseEmitter.event().comment("keepalive"));
            } catch (IOException | IllegalStateException e) {
                set.remove(emitter);
            }
        }));
    }

    int subscriberCount(UUID restaurantId) {
        Set<SseEmitter> set = subscribers.get(restaurantId);
        return set == null ? 0 : set.size();
    }
}
