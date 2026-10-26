package it.ristomanager.cloud.ai;

import java.util.List;

/**
 * The model's answer, with the figures needed to judge cost and speed. Duration is measured by us, not by the model server.
 * {@code toolCalls} is never null: it is empty when the model answered in words only. The content may be empty when it asked for tools.
 */
public record LlmReply(String content, String model, long promptTokens, long outputTokens, long durationMillis,
                       List<ToolCall> toolCalls) {

    public LlmReply {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public LlmReply(String content, String model, long promptTokens, long outputTokens, long durationMillis) {
        this(content, model, promptTokens, outputTokens, durationMillis, List.of());
    }
}
