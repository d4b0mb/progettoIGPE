package it.ristomanager.cloud.common;

import tools.jackson.databind.JsonNode;

/** Small helpers for reading JSON that came from outside, such as a model's answer. */
public final class Json {

    private Json() {
    }

    /**
     * The text of a JSON string, or null when the node is missing, null or anything else. Jackson 3's own
     * {@code textValue()} throws for a number or an object, which would turn an odd answer from the model into a crash.
     */
    public static String text(JsonNode node) {
        return node != null && node.isString() ? node.stringValue() : null;
    }
}
