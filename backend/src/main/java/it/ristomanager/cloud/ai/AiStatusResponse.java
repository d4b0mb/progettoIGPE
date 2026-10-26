package it.ristomanager.cloud.ai;

/** Payload of {@code GET /api/v1/ai/status}. */
public record AiStatusResponse(boolean enabled, boolean reachable, String model) {
}
