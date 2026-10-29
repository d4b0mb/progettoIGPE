package it.ristomanager.cloud.audit;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the settings of the activity log. */
@Configuration
@EnableConfigurationProperties(AuditProperties.class)
class AuditConfig {
}
