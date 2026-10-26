package it.ristomanager.cloud.ai;

import java.util.List;

/**
 * One turn of a conversation with the language model. The role is "system", "user" or "assistant".
 * Images are base64 text without a "data:" prefix; the list is empty when the turn has none.
 */
public record ChatMessage(String role, String content, List<String> images) {

    public ChatMessage {
        images = images == null ? List.of() : List.copyOf(images);
    }

    public ChatMessage(String role, String content) {
        this(role, content, List.of());
    }

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content, List.of());
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content, List.of());
    }

    public static ChatMessage userWithImages(String content, List<String> images) {
        return new ChatMessage("user", content, images);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content, List.of());
    }
}
