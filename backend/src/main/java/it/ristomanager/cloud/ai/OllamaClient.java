package it.ristomanager.cloud.ai;

import it.ristomanager.cloud.ai.AiException.Kind;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
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
    public LlmReply chat(List<ChatMessage> messages) {
        if (!props.enabled()) {
            throw new AiException(Kind.DISABLED, "The language model is disabled");
        }
        long start = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint("/api/chat"))
                    .timeout(props.requestTimeout())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody(messages)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            long millis = (System.nanoTime() - start) / 1_000_000;
            if (response.statusCode() != 200) {
                throw new AiException(Kind.BAD_RESPONSE, "Model server answered HTTP " + response.statusCode());
            }
            LlmReply reply = parse(response.body(), millis);
            // Only sizes and timings are logged: prompts and replies can hold guests' personal data.
            log.info("Language model answered: model={} durationMillis={} promptTokens={} outputTokens={}",
                    reply.model(), reply.durationMillis(), reply.promptTokens(), reply.outputTokens());
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

    private String requestBody(List<ChatMessage> messages) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", props.model());
        ArrayNode array = body.putArray("messages");
        for (ChatMessage message : messages) {
            array.addObject().put("role", message.role()).put("content", message.content());
        }
        body.put("stream", false);
        body.put("think", props.think());
        body.put("keep_alive", props.keepAlive());
        body.putObject("options")
                .put("temperature", props.temperature())
                .put("num_ctx", props.contextTokens());
        return mapper.writeValueAsString(body);
    }

    private LlmReply parse(String json, long durationMillis) {
        JsonNode root = mapper.readTree(json);
        String content = root.path("message").path("content").textValue();
        if (content == null) {
            throw new AiException(Kind.BAD_RESPONSE, "Model server answer has no message content");
        }
        String model = root.path("model").textValue();
        return new LlmReply(content, model == null ? props.model() : model,
                root.path("prompt_eval_count").asLong(0), root.path("eval_count").asLong(0), durationMillis);
    }

    private static AiException failed(Kind kind, String message, Throwable cause) {
        log.warn("Language model call failed: kind={}", kind);
        return new AiException(kind, message, cause);
    }
}
