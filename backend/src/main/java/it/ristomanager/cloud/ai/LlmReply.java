package it.ristomanager.cloud.ai;

/** The model's answer, with the figures needed to judge cost and speed. Duration is measured by us, not by the model server. */
public record LlmReply(String content, String model, long promptTokens, long outputTokens, long durationMillis) {
}
