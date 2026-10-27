package it.ristomanager.cloud.ai;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Chooses the language model implementation. Replace the bean here to change provider (speech: see VoiceConfig). */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
class AiConfig {

    @Bean
    LlmClient llmClient(AiProperties props) {
        return new OllamaClient(props);
    }
}
