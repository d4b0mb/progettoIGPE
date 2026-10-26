package it.ristomanager.cloud.ai;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The model asking for a tool to be run. {@code arguments} is always a JSON object (empty when the model gave none).
 * The model's output is untrusted: whoever executes a call must validate every argument.
 */
public record ToolCall(String name, JsonNode arguments) {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    public ToolCall {
        name = name == null ? "" : name;
        arguments = arguments == null || !arguments.isObject() ? MAPPER.createObjectNode() : arguments;
    }
}
