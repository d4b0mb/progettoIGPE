package it.ristomanager.cloud.ai;

import java.util.List;

/**
 * One turn of a conversation with the language model. The role is "system", "user", "assistant" or "tool".
 * Images are base64 text without a "data:" prefix; the list is empty when the turn has none.
 *
 * An assistant turn that asked for tools carries {@code toolCalls} (its content is then normally empty). A "tool"
 * turn is the result of one call: {@code toolName} says which tool, {@code content} is the result as JSON text.
 */
public record ChatMessage(String role, String content, List<String> images, List<ToolCall> toolCalls, String toolName) {

    public ChatMessage {
        images = images == null ? List.of() : List.copyOf(images);
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        toolName = toolName == null || toolName.isBlank() ? null : toolName;
    }

    public ChatMessage(String role, String content, List<String> images) {
        this(role, content, images, List.of(), null);
    }

    public ChatMessage(String role, String content) {
        this(role, content, List.of(), List.of(), null);
    }

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    public static ChatMessage userWithImages(String content, List<String> images) {
        return new ChatMessage("user", content, images);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content);
    }

    /** The assistant turn that asked for these tools to be run. */
    public static ChatMessage assistantToolCalls(List<ToolCall> toolCalls) {
        return new ChatMessage("assistant", "", List.of(), toolCalls, null);
    }

    /** The result of one tool call, handed back to the model. */
    public static ChatMessage toolResult(String toolName, String content) {
        return new ChatMessage("tool", content, List.of(), List.of(), toolName);
    }
}
