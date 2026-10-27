package it.ristomanager.cloud.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the language model of the application with a {@link ScriptedLlm}. Test classes that import the same
 * configurations share one Spring context, so they also share this one object: call {@link ScriptedLlm#reset()} first.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ScriptedLlmConfig {

    @Bean
    @Primary
    ScriptedLlm scriptedLlm() {
        return new ScriptedLlm();
    }
}
