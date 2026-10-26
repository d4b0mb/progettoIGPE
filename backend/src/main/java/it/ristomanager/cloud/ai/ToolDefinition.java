package it.ristomanager.cloud.ai;

import tools.jackson.databind.JsonNode;

/**
 * A function the model may ask to have called. {@code parameters} is a JSON schema describing the arguments
 * (an object with "properties" and "required").
 */
public record ToolDefinition(String name, String description, JsonNode parameters) {
}
