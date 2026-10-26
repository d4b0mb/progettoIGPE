package it.ristomanager.cloud.ai;

import it.ristomanager.cloud.common.Json;
import it.ristomanager.cloud.ai.AiException.Kind;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Talks to a local Ollama server. Nothing leaves the restaurant's own machine.
 *
 * Uses the JDK's own HTTP client so no extra library is needed. Kept free of Spring annotations: it is
 * created in {@link AiConfig}, which is where the model provider is chosen.
 */
public class OllamaClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaClient.class);
    private static final Duration REACHABILITY_TIMEOUT = Duration.ofSeconds(2);

    private final AiProperties props;
    private final HttpClient http;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public OllamaClient(AiProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder().connectTimeout(props.connectTimeout()).build();
    }

    @Override
    public LlmReply chat(List<ChatMessage> messages, ChatOptions options) {
        if (!props.enabled()) {
            throw new AiException(Kind.DISABLED, "The language model is disabled");
        }
        long start = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint("/api/chat"))
                    .timeout(options.timeout() != null ? options.timeout() : props.requestTimeout())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody(messages, options)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            long millis = (System.nanoTime() - start) / 1_000_000;
            if (response.statusCode() != 200) {
                throw new AiException(Kind.BAD_RESPONSE, "Model server answered HTTP " + response.statusCode());
            }
            LlmReply reply = parse(response.body(), millis);
            // Only sizes and timings are logged: prompts and replies can hold guests' personal data.
            log.info("Language model answered: model={} durationMillis={} promptTokens={} outputTokens={} toolCalls={}",
                    reply.model(), reply.durationMillis(), reply.promptTokens(), reply.outputTokens(),
                    reply.toolCalls().size());
            return reply;
        } catch (AiException e) {
            log.warn("Language model call failed: kind={}", e.kind());
            throw e;
        } catch (HttpTimeoutException e) {
            throw failed(Kind.TIMEOUT, "Model server did not answer in time", e);
        } catch (IOException | IllegalArgumentException e) {
            throw failed(Kind.UNREACHABLE, "Model server could not be reached", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failed(Kind.UNREACHABLE, "Interrupted while waiting for the model server", e);
        } catch (JacksonException e) {
            throw failed(Kind.BAD_RESPONSE, "Model server answered with invalid JSON", e);
        }
    }

    @Override
    public boolean isReachable() {
        if (!props.enabled()) {
            return false;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint("/api/tags"))
                    .timeout(REACHABILITY_TIMEOUT)
                    .GET()
                    .build();
            return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String modelName() {
        return props.model();
    }

    private URI endpoint(String path) {
        String base = props.baseUrl();
        return URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + path);
    }

    private String requestBody(List<ChatMessage> messages, ChatOptions options) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", props.model());
        ArrayNode array = body.putArray("messages");
        for (ChatMessage message : messages) {
            ObjectNode node = array.addObject();
            node.put("role", message.role()).put("content", message.content() == null ? "" : message.content());
            if (!message.images().isEmpty()) {
                ArrayNode images = node.putArray("images");
                message.images().forEach(images::add);
            }
            if (!message.toolCalls().isEmpty()) {
                ArrayNode calls = node.putArray("tool_calls");
                for (ToolCall call : message.toolCalls()) {
                    ObjectNode function = calls.addObject().putObject("function");
                    function.put("name", call.name());
                    function.set("arguments", call.arguments());
                }
            }
            if (message.toolName() != null) {
                node.put("tool_name", message.toolName());
            }
        }
        if (options.tools() != null && !options.tools().isEmpty()) {
            ArrayNode toolArray = body.putArray("tools");
            for (ToolDefinition tool : options.tools()) {
                ObjectNode function = toolArray.addObject().put("type", "function").putObject("function");
                function.put("name", tool.name());
                function.put("description", tool.description() == null ? "" : tool.description());
                function.set("parameters", tool.parameters() != null ? tool.parameters() : mapper.createObjectNode());
            }
        }
        body.put("stream", false);
        body.put("think", options.think() != null ? options.think() : props.think());
        if (options.format() != null) {
            body.set("format", options.format());
        }
        body.put("keep_alive", props.keepAlive());
        body.putObject("options")
                .put("temperature", props.temperature())
                .put("num_ctx", props.contextTokens());
        return mapper.writeValueAsString(body);
    }

    private LlmReply parse(String json, long durationMillis) {
        JsonNode root = mapper.readTree(json);
        JsonNode message = root.path("message");
        String content = Json.text(message.path("content"));
        List<ToolCall> toolCalls = toolCalls(message.path("tool_calls"));
        // Words, tool calls or both are an answer. Neither is nothing the caller can use.
        if ((content == null || content.isBlank()) && toolCalls.isEmpty()) {
            throw new AiException(Kind.BAD_RESPONSE, "Model server answer has no message content");
        }
        String model = Json.text(root.path("model"));
        return new LlmReply(content == null ? "" : content, model == null ? props.model() : model,
                root.path("prompt_eval_count").asLong(0), root.path("eval_count").asLong(0), durationMillis, toolCalls);
    }

    /**
     * Reads {@code message.tool_calls}. The arguments are normally an object; some models send them as a string holding
     * JSON, which is parsed too. Anything else becomes an empty object, so the tool answers "missing argument" and the
     * model can try again. Entries without a function name are skipped.
     */
    private List<ToolCall> toolCalls(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode entry : node) {
            JsonNode function = entry.path("function");
            String name = Json.text(function.path("name"));
            if (name == null || name.isBlank()) {
                continue;
            }
            JsonNode arguments = function.path("arguments");
            String asText = Json.text(arguments);
            if (asText != null) {
                try {
                    arguments = mapper.readTree(asText);
                } catch (JacksonException e) {
                    arguments = null;
                }
            }
            calls.add(new ToolCall(name, arguments));
        }
        return calls;
    }

    private static AiException failed(Kind kind, String message, Throwable cause) {
        log.warn("Language model call failed: kind={}", kind);
        return new AiException(kind, message, cause);
    }
}
