package it.ristomanager.cloud.floor.draft;

import it.ristomanager.cloud.floor.FloorTypes.ObstacleType;
import it.ristomanager.cloud.floor.FloorTypes.TableShape;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The JSON schema the model's answer must follow (Ollama structured output). Every coordinate is a fraction
 * of the whole image: x from the left edge, y from the top edge. Rotation is in degrees, clockwise.
 */
final class FloorDraftSchema {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private FloorDraftSchema() {
    }

    static ObjectNode schema() {
        Map<String, ObjectNode> fields = new LinkedHashMap<>();
        fields.put("room", objectOf(numbers("left", "top", "right", "bottom")));
        fields.put("tables", arrayOf(placed("shape", Stream.of(TableShape.values()).map(Enum::name).toList())));
        fields.put("elements", arrayOf(placed("type", Stream.of(ObstacleType.values()).map(Enum::name).toList())));
        fields.put("notes", MAPPER.createObjectNode().put("type", "string"));
        return objectOf(fields);
    }

    /** An item with a kind (enum) and a position, size and rotation. */
    private static ObjectNode placed(String kindField, List<String> kinds) {
        Map<String, ObjectNode> fields = new LinkedHashMap<>();
        ObjectNode kind = MAPPER.createObjectNode().put("type", "string");
        ArrayNode values = kind.putArray("enum");
        kinds.forEach(values::add);
        fields.put(kindField, kind);
        fields.putAll(numbers("cx", "cy", "width", "height", "rotation"));
        return objectOf(fields);
    }

    private static Map<String, ObjectNode> numbers(String... names) {
        Map<String, ObjectNode> fields = new LinkedHashMap<>();
        for (String name : names) {
            fields.put(name, MAPPER.createObjectNode().put("type", "number"));
        }
        return fields;
    }

    /** An object whose listed fields are all required. */
    private static ObjectNode objectOf(Map<String, ObjectNode> fields) {
        ObjectNode object = MAPPER.createObjectNode().put("type", "object");
        ObjectNode properties = object.putObject("properties");
        ArrayNode required = object.putArray("required");
        fields.forEach((name, definition) -> {
            properties.set(name, definition);
            required.add(name);
        });
        return object;
    }

    private static ObjectNode arrayOf(ObjectNode items) {
        ObjectNode array = MAPPER.createObjectNode().put("type", "array");
        array.set("items", items);
        return array;
    }
}
