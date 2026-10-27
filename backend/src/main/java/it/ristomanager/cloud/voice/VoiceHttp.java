package it.ristomanager.cloud.voice;

import it.ristomanager.cloud.ai.AiException;
import it.ristomanager.cloud.ai.AiException.Kind;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * What the two OpenAI-compatible engines have in common: building an address, sending a request with an overall deadline
 * and the same error mapping as the language model client, and the "is something listening" probe.
 *
 * No message built here ever holds a response body, a transcript or any spoken text.
 */
final class VoiceHttp {

    static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

    private VoiceHttp() {
    }

    static HttpClient newClient() {
        return HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    static URI endpoint(String baseUrl, String path) {
        return URI.create((baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl) + path);
    }

    /**
     * Sends the request and waits for the whole answer (headers and body) for at most {@code timeout}. The plain
     * request timeout of the JDK client only covers the arrival of the headers, and a speech engine may stream its body.
     *
     * @throws AiException TIMEOUT when the deadline passes, UNREACHABLE for connection problems or an interruption
     */
    static <T> HttpResponse<T> send(HttpClient http, HttpRequest request, HttpResponse.BodyHandler<T> handler,
                                    Duration timeout) {
        CompletableFuture<HttpResponse<T>> future;
        try {
            future = http.sendAsync(request, handler);
        } catch (IllegalArgumentException | SecurityException e) {
            throw new AiException(Kind.UNREACHABLE, "Speech server could not be reached", e);
        }
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new AiException(Kind.TIMEOUT, "Speech server did not answer in time", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof HttpTimeoutException) {
                throw new AiException(Kind.TIMEOUT, "Speech server did not answer in time", cause);
            }
            throw new AiException(Kind.UNREACHABLE, "Speech server could not be reached",
                    cause instanceof IOException ? cause : e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new AiException(Kind.UNREACHABLE, "Interrupted while waiting for the speech server", e);
        }
    }

    /**
     * Asks {@code GET {baseUrl}/v1/models} with a 2 second limit. Any HTTP answer below 500 (200, 404, 401 and so on)
     * counts as "the server is up": the probe only asks whether something is listening, because not every engine has a
     * models list or a health path (Kokoro-FastAPI documents none and may answer 404). A connection error, a timeout or
     * a 5xx answer is "not available". Never throws.
     */
    static boolean isListening(HttpClient http, String baseUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint(baseUrl, "/v1/models"))
                    .timeout(PROBE_TIMEOUT)
                    .GET()
                    .build();
            return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() < 500;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** The MIME type without parameters such as ";codecs=opus", in lower case; null when blank or missing. */
    static String baseMimeType(String contentType) {
        if (contentType == null) {
            return null;
        }
        int semicolon = contentType.indexOf(';');
        String base = (semicolon >= 0 ? contentType.substring(0, semicolon) : contentType).trim()
                .toLowerCase(Locale.ROOT);
        return base.isEmpty() ? null : base;
    }

    /** The primary part of a language tag in lower case ("it-IT" gives "it"); null when blank or missing. */
    static String primaryLanguage(String languageTag) {
        if (languageTag == null || languageTag.isBlank()) {
            return null;
        }
        String tag = languageTag.trim().toLowerCase(Locale.ROOT);
        int dash = tag.indexOf('-');
        int underscore = tag.indexOf('_');
        int cut = dash < 0 ? underscore : (underscore < 0 ? dash : Math.min(dash, underscore));
        return cut > 0 ? tag.substring(0, cut) : tag;
    }
}
