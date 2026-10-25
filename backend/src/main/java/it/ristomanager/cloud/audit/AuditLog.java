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

    private final JdbcClient jdbc;
    private final TransactionTemplate separateTransaction;

    AuditLog(JdbcClient jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
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

    /** Something with no restaurant: an unknown email trying to sign in, or the nightly cleanup. */
    public void recordUnscoped(String actorLabel, String action, String outcome, Map<String, String> details) {
        try {
            String[] where = origin();
            separateTransaction.executeWithoutResult(status ->
                    jdbc.sql("select audit_record_unscoped(:label, :action, :outcome, cast(:details as jsonb), :ip, :ua)")
                            .param("label", actorLabel).param("action", action).param("outcome", outcome)
                            .param("details", json(details)).param("ip", where[0]).param("ua", where[1])
                            .query().singleRow());
        } catch (RuntimeException e) {
            log.error("Could not write activity log entry {}", action, e);
        }
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
