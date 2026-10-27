package it.ristomanager.cloud.support;

import it.ristomanager.cloud.ai.AiException;
import it.ristomanager.cloud.ai.ChatMessage;
import it.ristomanager.cloud.ai.ChatOptions;
import it.ristomanager.cloud.ai.LlmClient;
import it.ristomanager.cloud.ai.LlmReply;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A language model the test controls: it replies with whatever the test queued (a reply, or an {@link AiException} to
 * throw) and remembers what it was asked. Integration tests use it in place of Ollama through {@link ScriptedLlmConfig}.
 */
public class ScriptedLlm implements LlmClient {

    /** Replies the test queued: an {@link LlmReply}, or an {@link AiException} to throw. */
    public final Deque<Object> script = new ArrayDeque<>();
    public final List<ChatOptions> seenOptions = new ArrayList<>();
    public final List<List<ChatMessage>> seenMessages = new ArrayList<>();
    /** What {@link #isReachable()} answers. */
    public volatile boolean reachable = true;

    @Override
    public synchronized LlmReply chat(List<ChatMessage> messages, ChatOptions options) {
        seenMessages.add(List.copyOf(messages));
        seenOptions.add(options);
        Object next = script.poll();
        if (next == null) {
            throw new IllegalStateException("the test queued no more model replies");
        }
        if (next instanceof AiException e) {
            throw e;
        }
        return (LlmReply) next;
    }

    @Override
    public boolean isReachable() {
        return reachable;
    }

    @Override
    public String modelName() {
        return "scripted";
    }

    /** Forgets everything between tests (the Spring context, and so this object, is shared by the test classes). */
    public synchronized void reset() {
        script.clear();
        seenOptions.clear();
        seenMessages.clear();
        reachable = true;
    }

    /** How many times the model was called since the last reset. */
    public synchronized int calls() {
        return seenOptions.size();
    }
}
