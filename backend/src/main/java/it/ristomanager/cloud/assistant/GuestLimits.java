package it.ristomanager.cloud.assistant;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How much strangers on a message channel may use the model. Lower than the staff limits ({@link AiLimits}) and counted in
 * a gate of their own, so a flood of customer messages can never slow the waiters' assistant.
 *
 * @param maxConcurrent        guest model calls running at the same time, across all guests
 * @param perSubjectPerMinute  guest requests one sender (one verified identity on one channel) may start in any 60 seconds
 */
@ConfigurationProperties("app.ai.guest-limits")
public record GuestLimits(@DefaultValue("1") int maxConcurrent, @DefaultValue("6") int perSubjectPerMinute) {
}
