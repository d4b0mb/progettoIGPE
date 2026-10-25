package it.ristomanager.cloud.retention;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.retention")
public record RetentionProperties(boolean enabled, int guestDataDays, int auditDays) {
}
