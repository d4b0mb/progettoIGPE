package it.ristomanager.cloud.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import it.ristomanager.cloud.ai.AiException.Kind;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OllamaClientTest {

    private static final String OLLAMA_REPLY = """
            {"model":"test-model","message":{"role":"assistant","content":"Ciao"},
             "prompt_eval_count":12,"eval_count":7,"done":true}""";

    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> chatBody = new AtomicReference<>();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void chatReturnsContentModelAndTokenCounts() throws Exception {
        start("/api/chat", 200, OLLAMA_REPLY, 0);

        LlmReply reply = client(true, baseUrl(), Duration.ofSeconds(5)).chat(List.of(ChatMessage.user("Hi")));

        assertThat(reply.content()).isEqualTo("Ciao");
        assertThat(reply.model()).isEqualTo("test-model");
        assertThat(reply.promptTokens()).isEqualTo(12);
        assertThat(reply.outputTokens()).isEqualTo(7);
        assertThat(reply.durationMillis()).isNotNegative();
    }

    @Test
    void requestBodyCarriesSettingsAndMessagesInOrder() throws Exception {
        start("/api/chat", 200, OLLAMA_REPLY, 0);

        client(true, baseUrl(), Duration.ofSeconds(5)).chat(List.of(
                ChatMessage.system("Be brief"), ChatMessage.user("Question"), ChatMessage.assistant("Answer"),
                ChatMessage.user("Follow-up")));

        JsonNode body = JsonMapper.builder().build().readTree(chatBody.get());
        assertThat(body.path("model").textValue()).isEqualTo("test-model");
        assertThat(body.path("stream").toString()).isEqualTo("false");
        assertThat(body.path("think").toString()).isEqualTo("false");
        assertThat(body.path("keep_alive").textValue()).isEqualTo("30m");
        assertThat(body.path("options").path("temperature").toString()).isEqualTo("0.2");
        assertThat(body.path("options").path("num_ctx").toString()).isEqualTo("8192");
        JsonNode messages = body.path("messages");
        assertThat(messages.size()).isEqualTo(4);
        String[] roles = {"system", "user", "assistant", "user"};
        String[] contents = {"Be brief", "Question", "Answer", "Follow-up"};
        for (int i = 0; i < roles.length; i++) {
            assertThat(messages.get(i).path("role").textValue()).isEqualTo(roles[i]);
            assertThat(messages.get(i).path("content").textValue()).isEqualTo(contents[i]);
        }
    }

    @Test
    void slowServerIsATimeout() throws Exception {
        start("/api/chat", 200, OLLAMA_REPLY, 1500);

        assertThatThrownBy(() -> client(true, baseUrl(), Duration.ofMillis(300)).chat(List.of(ChatMessage.user("Hi"))))
                .isInstanceOfSatisfying(AiException.class, e -> assertThat(e.kind()).isEqualTo(Kind.TIMEOUT));
    }

    @Test
    void httpErrorIsBadResponse() throws Exception {
        start("/api/chat", 500, "boom", 0);

        assertThatThrownBy(() -> client(true, baseUrl(), Duration.ofSeconds(5)).chat(List.of(ChatMessage.user("Hi"))))
                .isInstanceOfSatisfying(AiException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.BAD_RESPONSE);
                    assertThat(e.getMessage()).contains("500").doesNotContain("boom");
                });
    }

    @Test
    void answerWithoutContentIsBadResponse() throws Exception {
        start("/api/chat", 200, "{\"model\":\"test-model\",\"message\":{\"role\":\"assistant\"}}", 0);

        assertThatThrownBy(() -> client(true, baseUrl(), Duration.ofSeconds(5)).chat(List.of(ChatMessage.user("Hi"))))
                .isInstanceOfSatisfying(AiException.class, e -> assertThat(e.kind()).isEqualTo(Kind.BAD_RESPONSE));
    }

    @Test
    void nothingListeningIsUnreachable() throws Exception {
        String deadUrl = deadUrl();

        assertThatThrownBy(() -> client(true, deadUrl, Duration.ofSeconds(5)).chat(List.of(ChatMessage.user("Hi"))))
                .isInstanceOfSatisfying(AiException.class, e -> assertThat(e.kind()).isEqualTo(Kind.UNREACHABLE));
    }

    @Test
    void disabledNeverCallsTheServer() throws Exception {
        start("/api/chat", 200, OLLAMA_REPLY, 0);

        assertThatThrownBy(() -> client(false, baseUrl(), Duration.ofSeconds(5)).chat(List.of(ChatMessage.user("Hi"))))
                .isInstanceOfSatisfying(AiException.class, e -> assertThat(e.kind()).isEqualTo(Kind.DISABLED));
        assertThat(requests.get()).isZero();
    }

    @Test
    void reachableWhenTagsAnswers200() throws Exception {
        start("/api/tags", 200, "{\"models\":[]}", 0);

        assertThat(client(true, baseUrl(), Duration.ofSeconds(5)).isReachable()).isTrue();
    }

    @Test
    void notReachableWhenTagsAnswers500() throws Exception {
        start("/api/tags", 500, "boom", 0);

        assertThat(client(true, baseUrl(), Duration.ofSeconds(5)).isReachable()).isFalse();
    }

    @Test
    void notReachableWhenNothingListens() throws Exception {
        assertThat(client(true, deadUrl(), Duration.ofSeconds(5)).isReachable()).isFalse();
    }

    @Test
    void imagesAreSentOnlyOnTheMessagesThatHaveThem() throws Exception {
        start("/api/chat", 200, OLLAMA_REPLY, 0);

        client(true, baseUrl(), Duration.ofSeconds(5)).chat(List.of(
                ChatMessage.system("Be brief"), ChatMessage.userWithImages("Look", List.of("QUJD", "REVG"))));

        JsonNode messages = JsonMapper.builder().build().readTree(chatBody.get()).path("messages");
        assertThat(messages.get(0).has("images")).isFalse();
        JsonNode images = messages.get(1).path("images");
        assertThat(images.size()).isEqualTo(2);
        assertThat(images.get(0).textValue()).isEqualTo("QUJD");
        assertThat(images.get(1).textValue()).isEqualTo("REVG");
    }

    @Test
    void formatIsSentOnlyWhenGiven() throws Exception {
        start("/api/chat", 200, OLLAMA_REPLY, 0);
        JsonMapper json = JsonMapper.builder().build();
        JsonNode schema = json.readTree("{\"type\":\"object\",\"required\":[\"a\"]}");

        client(true, baseUrl(), Duration.ofSeconds(5))
                .chat(List.of(ChatMessage.user("Hi")), new ChatOptions(schema, null, null));
        JsonNode withFormat = json.readTree(chatBody.get());
        client(true, baseUrl(), Duration.ofSeconds(5)).chat(List.of(ChatMessage.user("Hi")), ChatOptions.defaults());
        JsonNode withoutFormat = json.readTree(chatBody.get());

        assertThat(withFormat.path("format").path("type").textValue()).isEqualTo("object");
        assertThat(withFormat.path("format").path("required").get(0).textValue()).isEqualTo("a");
        assertThat(withoutFormat.has("format")).isFalse();
    }

    @Test
    void optionsThinkOverridesTheConfiguredValue() throws Exception {
        start("/api/chat", 200, OLLAMA_REPLY, 0);
        OllamaClient client = client(true, baseUrl(), Duration.ofSeconds(5), true);
        JsonMapper json = JsonMapper.builder().build();

        client.chat(List.of(ChatMessage.user("Hi")));
        assertThat(json.readTree(chatBody.get()).path("think").toString()).isEqualTo("true");

        client.chat(List.of(ChatMessage.user("Hi")), new ChatOptions(null, false, null));
        assertThat(json.readTree(chatBody.get()).path("think").toString()).isEqualTo("false");
    }

    @Test
    void optionsTimeoutOverridesTheConfiguredValue() throws Exception {
        start("/api/chat", 200, OLLAMA_REPLY, 1500);
        OllamaClient client = client(true, baseUrl(), Duration.ofSeconds(30));

        assertThatThrownBy(() -> client.chat(List.of(ChatMessage.user("Hi")),
                new ChatOptions(null, null, Duration.ofMillis(300))))
                .isInstanceOfSatisfying(AiException.class, e -> assertThat(e.kind()).isEqualTo(Kind.TIMEOUT));
    }

    // ---- tool calling

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static ToolDefinition findReservationsTool() {
        return new ToolDefinition("find_reservations", "Look up bookings.", JSON.readTree("""
                {"type":"object","properties":{"date":{"type":"string"}},"required":[]}"""));
    }

    /** A JSON string value (not an object), the way some models send tool arguments. */
    private static JsonNode jsonString(String text) {
        return JSON.readTree(JSON.writeValueAsString(text));
    }

    /** A reply whose message carries one tool call; {@code arguments} is put in as given (object or string). */
    private static String replyWithToolCall(String content, JsonNode arguments) {
        var message = JSON.createObjectNode();
        message.put("role", "assistant");
        if (content != null) {
            message.put("content", content);
        }
        var function = message.putArray("tool_calls").addObject().putObject("function");
        function.put("name", "find_reservations");
        function.set("arguments", arguments);
        var root = JSON.createObjectNode();
        root.put("model", "test-model");
        root.set("message", message);
        root.put("done", true);
        return JSON.writeValueAsString(root);
    }

    @Test
    void toolsAreSerialisedAsFunctions() throws Exception {
        start("/api/chat", 200, OLLAMA_REPLY, 0);

        client(true, baseUrl(), Duration.ofSeconds(5)).chat(List.of(ChatMessage.user("Hi")),
                ChatOptions.withTools(List.of(findReservationsTool())));

        JsonNode tool = JSON.readTree(chatBody.get()).path("tools").get(0);
        assertThat(tool.path("type").textValue()).isEqualTo("function");
        assertThat(tool.path("function").path("name").textValue()).isEqualTo("find_reservations");
        assertThat(tool.path("function").path("description").textValue()).isEqualTo("Look up bookings.");
        assertThat(tool.path("function").path("parameters").path("type").textValue()).isEqualTo("object");
        assertThat(tool.path("function").path("parameters").path("properties").path("date").path("type").textValue())
                .isEqualTo("string");
    }

    @Test
    void noToolsKeyWhenNoToolsAreGiven() throws Exception {
        start("/api/chat", 200, OLLAMA_REPLY, 0);
        OllamaClient client = client(true, baseUrl(), Duration.ofSeconds(5));

        client.chat(List.of(ChatMessage.user("Hi")));
        assertThat(JSON.readTree(chatBody.get()).has("tools")).isFalse();

        client.chat(List.of(ChatMessage.user("Hi")), ChatOptions.withTools(List.of()));
        assertThat(JSON.readTree(chatBody.get()).has("tools")).isFalse();
    }

    @Test
    void replayedToolCallsAndToolResultsAreSerialised() throws Exception {
        start("/api/chat", 200, OLLAMA_REPLY, 0);
        ToolCall call = new ToolCall("find_reservations", JSON.readTree("{\"date\":\"2026-10-26\"}"));

        client(true, baseUrl(), Duration.ofSeconds(5)).chat(List.of(
                ChatMessage.user("Who is coming tonight?"),
                ChatMessage.assistantToolCalls(List.of(call)),
                ChatMessage.toolResult("find_reservations", "{\"count\":0}")));

        JsonNode messages = JSON.readTree(chatBody.get()).path("messages");
        JsonNode replayed = messages.get(1);
        assertThat(replayed.path("role").textValue()).isEqualTo("assistant");
        assertThat(replayed.path("content").textValue()).isEmpty();
        assertThat(replayed.path("tool_calls").size()).isEqualTo(1);
        assertThat(replayed.path("tool_calls").get(0).path("function").path("name").textValue())
                .isEqualTo("find_reservations");
        assertThat(replayed.path("tool_calls").get(0).path("function").path("arguments").path("date").textValue())
                .isEqualTo("2026-10-26");
        JsonNode result = messages.get(2);
        assertThat(result.path("role").textValue()).isEqualTo("tool");
        assertThat(result.path("tool_name").textValue()).isEqualTo("find_reservations");
        assertThat(result.path("content").textValue()).isEqualTo("{\"count\":0}");
        // Plain turns carry neither field.
        assertThat(messages.get(0).has("tool_calls")).isFalse();
        assertThat(messages.get(0).has("tool_name")).isFalse();
    }

    @Test
    void toolCallsInTheReplyAreParsedWithObjectArguments() throws Exception {
        start("/api/chat", 200, replyWithToolCall("", JSON.readTree("{\"date\":\"2026-10-26\",\"name\":\"rossi\"}")), 0);

        LlmReply reply = client(true, baseUrl(), Duration.ofSeconds(5)).chat(List.of(ChatMessage.user("Hi")));

        assertThat(reply.toolCalls()).hasSize(1);
        assertThat(reply.toolCalls().get(0).name()).isEqualTo("find_reservations");
        assertThat(reply.toolCalls().get(0).arguments().path("date").textValue()).isEqualTo("2026-10-26");
        assertThat(reply.toolCalls().get(0).arguments().path("name").textValue()).isEqualTo("rossi");
    }

    @Test
    void toolCallArgumentsGivenAsAJsonStringAreParsed() throws Exception {
        start("/api/chat", 200, replyWithToolCall("", jsonString("{\"date\":\"2026-10-27\"}")), 0);

        LlmReply reply = client(true, baseUrl(), Duration.ofSeconds(5)).chat(List.of(ChatMessage.user("Hi")));

        assertThat(reply.toolCalls()).hasSize(1);
        assertThat(reply.toolCalls().get(0).arguments().isObject()).isTrue();
        assertThat(reply.toolCalls().get(0).arguments().path("date").textValue()).isEqualTo("2026-10-27");
    }

    @Test
    void unreadableToolCallArgumentsBecomeAnEmptyObject() throws Exception {
        start("/api/chat", 200, replyWithToolCall("", jsonString("this is not json")), 0);

        LlmReply reply = client(true, baseUrl(), Duration.ofSeconds(5)).chat(List.of(ChatMessage.user("Hi")));

        assertThat(reply.toolCalls()).hasSize(1);
        assertThat(reply.toolCalls().get(0).arguments().isObject()).isTrue();
        assertThat(reply.toolCalls().get(0).arguments().size()).isZero();
    }

    @Test
    void toolCallsWithEmptyOrMissingContentAreAccepted() throws Exception {
        String arguments = "{\"date\":\"2026-10-26\"}";
        start("/api/chat", 200, replyWithToolCall("", JSON.readTree(arguments)), 0);
        LlmReply empty = client(true, baseUrl(), Duration.ofSeconds(5)).chat(List.of(ChatMessage.user("Hi")));
        server.stop(0);

        start("/api/chat", 200, replyWithToolCall(null, JSON.readTree(arguments)), 0);
        LlmReply missing = client(true, baseUrl(), Duration.ofSeconds(5)).chat(List.of(ChatMessage.user("Hi")));

        assertThat(empty.content()).isEmpty();
        assertThat(empty.toolCalls()).hasSize(1);
        assertThat(missing.content()).isEmpty();
        assertThat(missing.toolCalls()).hasSize(1);
    }

    @Test
    void aReplyWithoutToolCallsHasAnEmptyList() throws Exception {
        start("/api/chat", 200, OLLAMA_REPLY, 0);

        LlmReply reply = client(true, baseUrl(), Duration.ofSeconds(5)).chat(List.of(ChatMessage.user("Hi")));

        assertThat(reply.toolCalls()).isNotNull().isEmpty();
    }

    @Test
    void neitherContentNorToolCallsIsBadResponse() throws Exception {
        start("/api/chat", 200,
                "{\"model\":\"test-model\",\"message\":{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[]}}", 0);

        assertThatThrownBy(() -> client(true, baseUrl(), Duration.ofSeconds(5)).chat(List.of(ChatMessage.user("Hi"))))
                .isInstanceOfSatisfying(AiException.class, e -> assertThat(e.kind()).isEqualTo(Kind.BAD_RESPONSE));
    }

    private static OllamaClient client(boolean enabled, String baseUrl, Duration requestTimeout) {
        return client(enabled, baseUrl, requestTimeout, false);
    }

    private static OllamaClient client(boolean enabled, String baseUrl, Duration requestTimeout, boolean think) {
        return new OllamaClient(new AiProperties(enabled, baseUrl, "test-model", 0.2, 8192, "30m", think,
                Duration.ofSeconds(2), requestTimeout));
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** A loopback address where nothing listens: a port that was free a moment ago. */
    private static String deadUrl() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return "http://127.0.0.1:" + socket.getLocalPort();
        }
    }

    private void start(String path, int status, String body, long delayMillis) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(path, exchange -> {
            requests.incrementAndGet();
            try (exchange) {
                String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (path.equals("/api/chat")) {
                    chatBody.set(request);
                }
                if (delayMillis > 0) {
                    Thread.sleep(delayMillis);
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            } catch (IOException e) {
                // The client gave up (timeout test); nothing to answer.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
    }
}
