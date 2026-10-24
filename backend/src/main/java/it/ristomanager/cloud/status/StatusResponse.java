package it.ristomanager.cloud.status;

/** Payload of {@code GET /api/v1/status}. */
public record StatusResponse(String application, String version, String database) {
}
