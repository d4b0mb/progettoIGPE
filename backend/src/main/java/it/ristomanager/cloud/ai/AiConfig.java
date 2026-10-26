package it.ristomanager.cloud.ai;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Chooses the model and speech implementations. Replace a bean here to change provider. */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
class AiConfig {

    @Bean
    LlmClient llmClient(AiProperties props) {
        return new OllamaClient(props);
    }

    @Bean
    SpeechSynthesizer speechSynthesizer() {
        return new NoSpeechSynthesizer();
    }

    @Bean
    SpeechRecognizer speechRecognizer() {
        return new NoSpeechRecognizer();
    }
}
