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

    private static OllamaClient client(boolean enabled, String baseUrl, Duration requestTimeout) {
        return new OllamaClient(new AiProperties(enabled, baseUrl, "test-model", 0.2, 8192, "30m", false,
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
