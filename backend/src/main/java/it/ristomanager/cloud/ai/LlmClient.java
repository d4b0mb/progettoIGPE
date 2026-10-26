package it.ristomanager.cloud.ai;

import java.util.List;

/**
 * The one door to a language model. Everything else in the application talks to this interface, never to a
 * specific product, so the model or the provider can be swapped by providing another implementation.
 */
public interface LlmClient {

    /** Sends the conversation with the configured settings and waits for the full answer. Throws {@link AiException} on any failure. */
    default LlmReply chat(List<ChatMessage> messages) {
        return chat(messages, ChatOptions.defaults());
    }

    /** Like {@link #chat(List)}, with per-request overrides. Throws {@link AiException} on any failure. */
    LlmReply chat(List<ChatMessage> messages, ChatOptions options);

    /** Whether the model server answers right now. Never throws. */
    boolean isReachable();

    String modelName();
}
