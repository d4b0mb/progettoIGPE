package it.ristomanager.cloud.voice;

import it.ristomanager.cloud.ai.NoSpeechRecognizer;
import it.ristomanager.cloud.ai.NoSpeechSynthesizer;
import it.ristomanager.cloud.ai.SpeechRecognizer;
import it.ristomanager.cloud.ai.SpeechSynthesizer;
import it.ristomanager.cloud.assistant.AiGate;
import it.ristomanager.cloud.assistant.AiLimits;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chooses the speech implementations. Replace a bean here to change provider (another implementation of the same two
 * interfaces, such as a hosted service). When voice is off the placeholders refuse every call.
 */
@Configuration
@EnableConfigurationProperties(VoiceProperties.class)
class VoiceConfig {

    @Bean
    SpeechRecognizer speechRecognizer(VoiceProperties props) {
        return props.enabled() ? new OpenAiCompatibleRecognizer(props.stt()) : new NoSpeechRecognizer();
    }

    @Bean
    SpeechSynthesizer speechSynthesizer(VoiceProperties props) {
        return props.enabled() ? new OpenAiCompatibleSynthesizer(props.tts()) : new NoSpeechSynthesizer();
    }

    /**
     * Voice has its own gate (a second instance of the assistant's {@link AiGate}, with its own counters and slots):
     * one spoken turn is three calls (listen, think, speak) and must not use up the assistant's budget.
     */
    @Bean
    AiGate voiceGate(VoiceProperties props) {
        return new AiGate(new AiLimits(props.limits().maxConcurrent(), props.limits().perUserPerMinute()));
    }
}
