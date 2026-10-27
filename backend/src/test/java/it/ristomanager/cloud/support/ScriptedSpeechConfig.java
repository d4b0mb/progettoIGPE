package it.ristomanager.cloud.support;

import it.ristomanager.cloud.ai.SpeechRecognizer;
import it.ristomanager.cloud.ai.SpeechSynthesizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the speech engines of the application with a {@link ScriptedSpeech}: the primary {@link SpeechRecognizer} and
 * {@link SpeechSynthesizer} are its two views. Test classes that import the same configurations share one Spring context,
 * so they also share this one object: call {@link ScriptedSpeech#reset()} first.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ScriptedSpeechConfig {

    /** The object the tests script and inspect (inject it by its own type). Not primary: the two views below are. */
    @Bean
    ScriptedSpeech scriptedSpeech() {
        return new ScriptedSpeech();
    }

    @Bean
    @Primary
    SpeechRecognizer scriptedRecognizer(ScriptedSpeech speech) {
        return speech.asRecognizer();
    }

    @Bean
    @Primary
    SpeechSynthesizer scriptedSynthesizer(ScriptedSpeech speech) {
        return speech.asSynthesizer();
    }
}
