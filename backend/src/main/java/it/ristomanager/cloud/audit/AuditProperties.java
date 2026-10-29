package it.ristomanager.cloud.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Limits on the activity log entries that have no restaurant (a failed sign-in or recovery for an unknown
 * email). Anyone on the internet can cause those, so they must not be able to fill the table. Entries of a
 * signed-in restaurant are never limited.
 *
 * @param unscopedPerAddressPerMinute entries one client address may cause in a minute
 * @param unscopedPerMinute           entries all addresses together may cause in a minute
 */
@ConfigurationProperties("app.audit")
public record AuditProperties(@DefaultValue("20") int unscopedPerAddressPerMinute,
                              @DefaultValue("200") int unscopedPerMinute) {
}
