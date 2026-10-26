package it.ristomanager.cloud.assistant;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How much the local model may be used at once. The model runs on one machine and answers one request at a time
 * per slot, so a few staff members pressing "send" together must not queue up unboundedly.
 *
 * @param maxConcurrent   model calls running at the same time, across all users
 * @param perUserPerMinute model-backed requests one person may start in any 60 seconds
 */
@ConfigurationProperties("app.ai.limits")
public record AiLimits(@DefaultValue("2") int maxConcurrent, @DefaultValue("12") int perUserPerMinute) {
}
