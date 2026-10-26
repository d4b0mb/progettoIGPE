package it.ristomanager.cloud.assistant;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the settings of the staff assistant. */
@Configuration
@EnableConfigurationProperties(AiLimits.class)
class AssistantConfig {
}
