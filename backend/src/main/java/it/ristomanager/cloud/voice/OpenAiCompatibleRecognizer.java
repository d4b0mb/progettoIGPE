package it.ristomanager.cloud.voice;

import it.ristomanager.cloud.ai.AiException;
import it.ristomanager.cloud.ai.AiException.Kind;
import it.ristomanager.cloud.ai.SpeechRecognizer;
import it.ristomanager.cloud.common.Json;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Listens through a local server that speaks the OpenAI audio API ({@code POST /v1/audio/transcriptions}), for example
 * faster-whisper / speaches. Uses the JDK's own HTTP client; the multipart body is built by hand.
 *
 * Kept free of Spring annotations: it is created in {@link VoiceConfig}. Audio and transcripts are never logged, only
 * sizes in bytes and durations.
 */
public class OpenAiCompatibleRecognizer implements SpeechRecognizer {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleRecognizer.class);
    private static final String CRLF = "\r\n";

    private final VoiceProperties.Stt props;
    private final HttpClient http = VoiceHttp.newClient();
    private final JsonMapper mapper = JsonMapper.builder().build();

    public OpenAiCompatibleRecognizer(VoiceProperties.Stt props) {
        this.props = props;
    }

    @Override
    public String transcribe(byte[] audio, String audioMimeType, String languageTag) {
        long start = System.nanoTime();
        try {
            String mime = VoiceHttp.baseMimeType(audioMimeType);
            String language = VoiceHttp.primaryLanguage(languageTag);
            if (language == null) {
                language = VoiceHttp.primaryLanguage(props.language());
            }
            String boundary = "----RistoManagerBoundary" + UUID.randomUUID().toString().replace("-", "");
            byte[] body = multipartBody(boundary, audio, mime, language);
            HttpRequest request = HttpRequest.newBuilder(VoiceHttp.endpoint(props.baseUrl(), "/v1/audio/transcriptions"))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> response = VoiceHttp.send(http, request, HttpResponse.BodyHandlers.ofString(),
                    props.timeout());
            if (response.statusCode() != 200) {
                throw new AiException(Kind.BAD_RESPONSE, "Speech server answered HTTP " + response.statusCode());
            }
            String text = parse(response.body());
            log.info("Speech recognised: audioBytes={} durationMillis={}", audio.length,
                    (System.nanoTime() - start) / 1_000_000);
            return text;
        } catch (AiException e) {
            log.warn("Speech recognition failed: kind={}", e.kind());
            throw e;
        } catch (IllegalArgumentException e) {
            log.warn("Speech recognition failed: kind={}", Kind.UNREACHABLE);
            throw new AiException(Kind.UNREACHABLE, "Speech server could not be reached", e);
        }
    }

    @Override
    public boolean isAvailable() {
        return VoiceHttp.isListening(http, props.baseUrl());
    }

    /** The transcript, or "" for silence. An answer that is not JSON, or has no string "text", is unusable. */
    private String parse(String json) {
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (JacksonException e) {
            throw new AiException(Kind.BAD_RESPONSE, "Speech server answered with invalid JSON", e);
        }
        String text = Json.text(root.path("text"));
        if (text == null) {
            throw new AiException(Kind.BAD_RESPONSE, "Speech server answer has no text");
        }
        return text.isBlank() ? "" : text;
    }

    private byte[] multipartBody(String boundary, byte[] audio, String mime, String language) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(audio.length + 1024);
        String contentType = mime != null ? mime : "application/octet-stream";
        write(out, "--" + boundary + CRLF
                + "Content-Disposition: form-data; name=\"file\"; filename=\"audio." + extension(mime) + "\"" + CRLF
                + "Content-Type: " + contentType + CRLF + CRLF);
        out.writeBytes(audio);
        write(out, CRLF);
        field(out, boundary, "model", props.model());
        field(out, boundary, "response_format", "json");
        if (language != null) {
            field(out, boundary, "language", language);
        }
        write(out, "--" + boundary + "--" + CRLF);
        return out.toByteArray();
    }

    private static void field(ByteArrayOutputStream out, String boundary, String name, String value) {
        write(out, "--" + boundary + CRLF
                + "Content-Disposition: form-data; name=\"" + name + "\"" + CRLF + CRLF
                + value + CRLF);
    }

    private static void write(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    /** The file extension engines use to pick a decoder; it must match the real container. */
    static String extension(String mime) {
        if (mime == null) {
            return "bin";
        }
        return switch (mime) {
            case "audio/webm" -> "webm";
            case "audio/ogg" -> "ogg";
            case "audio/mp4" -> "mp4";
            case "audio/x-m4a", "audio/m4a" -> "m4a";
            case "audio/aac" -> "aac";
            case "audio/wav", "audio/x-wav" -> "wav";
            case "audio/mpeg", "audio/mp3" -> "mp3";
            default -> "bin";
        };
    }
}
