package it.ristomanager.cloud.retention;

import it.ristomanager.cloud.audit.AuditLog;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Keeps personal data only as long as it is needed.
 *
 * Every night, guest details on visits that ended more than the retention period ago are erased (the
 * booking itself stays so history and counts add up), old entries of the activity log are deleted, and
 * long-dead login tokens are cleaned up. The work is done by one database function that runs with the
 * database owner's rights, because it crosses restaurants; see migrations V8 and V11 (V11 also clears ticket
 * and line notes and deletes revoked tokens).
 */
@Service
@EnableConfigurationProperties(RetentionProperties.class)
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    /** What one run removed. */
    public record Result(int reservationsAnonymised, int ticketsAnonymised, int auditDeleted, int tokensDeleted) {
    }

    private final JdbcClient jdbc;
    private final AuditLog audit;
    private final RetentionProperties props;

    RetentionService(JdbcClient jdbc, AuditLog audit, RetentionProperties props) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.props = props;
    }

    public Result purge(int guestDataDays, int auditDays) {
        return jdbc.sql("select * from retention_purge(:guest, :audit)")
                .param("guest", guestDataDays).param("audit", auditDays)
                .query((rs, n) -> new Result(rs.getInt("reservations_anonymised"), rs.getInt("tickets_anonymised"),
                        rs.getInt("audit_deleted"), rs.getInt("tokens_deleted")))
                .single();
    }

    /** 03:30 every night, when restaurants are closed. Safe to run more than once: it only touches what is already due. */
    @Scheduled(cron = "${app.retention.cron:0 30 3 * * *}")
    void nightly() {
        if (!props.enabled()) {
            return;
        }
        try {
            Result result = purge(props.guestDataDays(), props.auditDays());
            log.info("Data retention run: {}", result);
            audit.recordUnscoped("system", "RETENTION_RUN", "SUCCESS", Map.of(
                    "reservationsAnonymised", String.valueOf(result.reservationsAnonymised()),
                    "ticketsAnonymised", String.valueOf(result.ticketsAnonymised()),
                    "auditDeleted", String.valueOf(result.auditDeleted()),
                    "tokensDeleted", String.valueOf(result.tokensDeleted())));
        } catch (RuntimeException e) {
            log.error("Data retention run failed", e);
        }
    }
}
