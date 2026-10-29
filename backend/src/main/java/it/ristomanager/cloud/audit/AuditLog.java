package it.ristomanager.cloud.audit;

import it.ristomanager.cloud.security.CurrentUser;
import it.ristomanager.cloud.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;

/**
 * The activity log: who did what, when, from where. Entries are added, never changed.
 *
 * Each entry is written in its own transaction, so it is kept even if the action it describes is
 * rolled back (a failed attempt is exactly what you want to see). A problem writing the log is
 * reported to the server log but never breaks the action itself.
 */
@Service
public class AuditLog {

    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The entry that says how many failure entries without a restaurant were left out in a minute. */
    static final String SUPPRESSED_ACTION = "AUTH_FAILURES_SUPPRESSED";

    private final JdbcClient jdbc;
    private final TransactionTemplate separateTransaction;
    private final UnscopedFailureLimiter limiter;

    AuditLog(JdbcClient jdbc, PlatformTransactionManager transactions, UnscopedFailureLimiter limiter) {
        this.jdbc = jdbc;
        this.limiter = limiter;
        this.separateTransaction = new TransactionTemplate(transactions);
        this.separateTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** An action by the person making this request, in their restaurant. */
    public void record(String action, String targetType, String targetId, Map<String, String> details) {
        Optional<CurrentUser> actor = currentActor();
        if (actor.isEmpty()) {
            recordUnscoped("", action, "SUCCESS", details);
            return;
        }
        write(actor.get().restaurantId(), actor.get().userId(), "", action, targetType, targetId, "SUCCESS", details);
    }

    /** An action in a known restaurant by someone who may not be signed in yet (a failed sign-in, a recovery). */
    public void recordFor(UUID restaurantId, UUID actorUserId, String actorLabel, String action, String targetType,
                          String targetId, String outcome, Map<String, String> details) {
        write(restaurantId, actorUserId, actorLabel, action, targetType, targetId, outcome, details);
    }

    /**
     * Something with no restaurant: an unknown email trying to sign in, or the nightly cleanup.
     *
     * Failures are limited (see {@link UnscopedFailureLimiter}) because anyone can cause them: beyond the limit
     * the entry is not written, only counted, and one summary entry per minute says how many were left out.
     * Other entries (the nightly cleanup) are never limited.
     */
    public void recordUnscoped(String actorLabel, String action, String outcome, Map<String, String> details) {
        try {
            String[] where = origin();
            boolean failure = "FAILURE".equals(outcome);
            if (failure && !limiter.tryAdmit(where[0])) {
                writeSuppressionSummary();
                return;
            }
            insertUnscoped(actorLabel, action, outcome, details, where);
            if (failure) {
                writeSuppressionSummary();
            }
        } catch (RuntimeException e) {
            log.error("Could not write activity log entry {}", action, e);
        }
    }

    /** Writes the one summary entry for minutes that have finished, if entries were dropped in them. */
    @Scheduled(fixedDelay = 15_000, initialDelay = 15_000)
    void writeSuppressionSummary() {
        try {
            long dropped = limiter.takeSummary();
            if (dropped > 0) {
                insertUnscoped("system", SUPPRESSED_ACTION, "FAILURE", Map.of("dropped", Long.toString(dropped)),
                        new String[] {null, null});
                log.warn("{} failed sign-in or recovery attempts were not written to the activity log", dropped);
            }
        } catch (RuntimeException e) {
            log.error("Could not write activity log summary entry", e);
        }
    }

    private void insertUnscoped(String actorLabel, String action, String outcome, Map<String, String> details,
                                String[] where) {
        separateTransaction.executeWithoutResult(status ->
                jdbc.sql("select audit_record_unscoped(:label, :action, :outcome, cast(:details as jsonb), :ip, :ua)")
                        .param("label", actorLabel).param("action", action).param("outcome", outcome)
                        .param("details", json(details)).param("ip", where[0]).param("ua", where[1])
                        .query().singleRow());
    }

    private void write(UUID restaurantId, UUID actorUserId, String actorLabel, String action, String targetType,
                       String targetId, String outcome, Map<String, String> details) {
        try {
            String[] where = origin();
            // The entry belongs to the restaurant, so write it as that restaurant (row-level security).
            TenantContext.callAs(restaurantId, () -> {
                separateTransaction.executeWithoutResult(status ->
                        jdbc.sql("""
                                insert into audit_event (restaurant_id, actor_user_id, actor_label, action, target_type,
                                                         target_id, outcome, details, ip, user_agent)
                                values (:rid, :actor, :label, :action, :ttype, :tid, :outcome, cast(:details as jsonb), :ip, :ua)
                                """)
                                .param("rid", restaurantId).param("actor", actorUserId)
                                .param("label", cut(actorLabel, 254)).param("action", cut(action, 80))
                                .param("ttype", targetType).param("tid", cut(targetId, 80)).param("outcome", outcome)
                                .param("details", json(details)).param("ip", where[0]).param("ua", where[1])
                                .update());
                return null;
            });
        } catch (RuntimeException e) {
            log.error("Could not write activity log entry {}", action, e);
        }
    }

    static Optional<CurrentUser> currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth instanceof JwtAuthenticationToken token ? Optional.of(CurrentUser.from(token.getToken())) : Optional.empty();
    }

    /** Where the request came from: address and browser, both cut to a sensible length. */
    private static String[] origin() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            return new String[] {cut(request.getRemoteAddr(), 64), cut(request.getHeader("User-Agent"), 300)};
        }
        return new String[] {null, null};
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static String json(Map<String, String> details) {
        return JSON.writeValueAsString(details == null ? Map.of() : new LinkedHashMap<>(details));
    }
}
