package it.ristomanager.cloud.ai;

import java.time.Duration;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Per-request overrides of the configured model settings. A null component means "use the configured default".
 *
 * @param format a JSON schema the answer must follow (Ollama structured output)
 * @param think  whether the model may reason before answering
 * @param timeout how long to wait for the full answer
 * @param tools  functions the model may ask to have called; null or empty means none
 */
public record ChatOptions(JsonNode format, Boolean think, Duration timeout, List<ToolDefinition> tools) {

    public ChatOptions {
        tools = tools == null ? null : List.copyOf(tools);
    }

    public ChatOptions(JsonNode format, Boolean think, Duration timeout) {
        this(format, think, timeout, null);
    }

    public static ChatOptions defaults() {
        return new ChatOptions(null, null, null, null);
    }

    public static ChatOptions withTools(List<ToolDefinition> tools) {
        return new ChatOptions(null, null, null, tools);
    }
}
