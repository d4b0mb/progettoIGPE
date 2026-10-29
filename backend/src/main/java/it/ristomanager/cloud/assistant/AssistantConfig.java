package it.ristomanager.cloud.assistant;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Fallback;

/** Registers the settings of the assistant and the pieces only the guest side needs. */
@Configuration
@EnableConfigurationProperties({AiLimits.class, GuestLimits.class})
class AssistantConfig {

    /**
     * The guest gate: a second instance of {@link AiGate} with its own counters and slots (the same pattern as the voice
     * gate), so guests never use up the staff budget. Injected by name into {@link AssistantService}.
     */
    @Bean
    AiGate guestGate(GuestLimits limits) {
        return new AiGate(new AiLimits(limits.maxConcurrent(), limits.perSubjectPerMinute()));
    }

    /** The default hand-off does nothing. A later step provides a real one, which takes precedence over this fallback. */
    @Bean
    @Fallback
    GuestHandoff noOpGuestHandoff() {
        return (guest, reason) -> {
        };
    }
}
