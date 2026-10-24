package it.ristomanager.cloud.tenant;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The restaurant whose data the current thread may touch. Set once per request
 * from the verified login token; {@link TenantDataSource} copies it into the
 * database session so row-level security can enforce it.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static Optional<UUID> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static void set(UUID restaurantId) {
        CURRENT.set(restaurantId);
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** Runs the action as the given restaurant, then restores whatever was set before. */
    public static <T> T callAs(UUID restaurantId, Supplier<T> action) {
        UUID previous = CURRENT.get();
        CURRENT.set(restaurantId);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
