package it.ristomanager.cloud.ai;

import java.time.Duration;
import tools.jackson.databind.JsonNode;

/**
 * Per-request overrides of the configured model settings. A null component means "use the configured default".
 *
 * @param format a JSON schema the answer must follow (Ollama structured output)
 * @param think  whether the model may reason before answering
 * @param timeout how long to wait for the full answer
 */
public record ChatOptions(JsonNode format, Boolean think, Duration timeout) {

    public static ChatOptions defaults() {
        return new ChatOptions(null, null, null);
    }
}
