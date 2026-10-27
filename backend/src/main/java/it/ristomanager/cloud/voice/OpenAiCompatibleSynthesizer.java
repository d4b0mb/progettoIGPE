package it.ristomanager.cloud.voice;

import it.ristomanager.cloud.ai.AiException;
import it.ristomanager.cloud.ai.AiException.Kind;
import it.ristomanager.cloud.ai.SpeechAudio;
import it.ristomanager.cloud.ai.SpeechSynthesizer;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Speaks through a local server that speaks the OpenAI audio API ({@code POST /v1/audio/speech}), for example
 * Kokoro-FastAPI. Uses the JDK's own HTTP client.
 *
 * Kept free of Spring annotations: it is created in {@link VoiceConfig}. The text is never logged, only sizes in
 * bytes and durations.
 */
public class OpenAiCompatibleSynthesizer implements SpeechSynthesizer {

    /** The largest audio accepted back from the engine. */
    static final int MAX_AUDIO_BYTES = 10 * 1024 * 1024;

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleSynthesizer.class);

    private final VoiceProperties.Tts props;
    private final HttpClient http = VoiceHttp.newClient();
    private final JsonMapper mapper = JsonMapper.builder().build();

    public OpenAiCompatibleSynthesizer(VoiceProperties.Tts props) {
        this.props = props;
    }

    @Override
    public SpeechAudio speak(String text, String languageTag, String voice) {
        long start = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder(VoiceHttp.endpoint(props.baseUrl(), "/v1/audio/speech"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody(text, languageTag, voice)))
                    .build();
            HttpResponse<byte[]> response = VoiceHttp.send(http, request, HttpResponse.BodyHandlers.ofByteArray(),
                    props.timeout());
            if (response.statusCode() != 200) {
                throw new AiException(Kind.BAD_RESPONSE, "Speech server answered HTTP " + response.statusCode());
            }
            byte[] bytes = response.body();
            if (bytes == null || bytes.length == 0) {
                throw new AiException(Kind.BAD_RESPONSE, "Speech server answered with no audio");
            }
            if (bytes.length > MAX_AUDIO_BYTES) {
                throw new AiException(Kind.BAD_RESPONSE, "Speech server audio is too large");
            }
            String mime = VoiceHttp.baseMimeType(response.headers().firstValue("Content-Type").orElse(null));
            if (mime == null) {
                mime = mimeForFormat(props.format());
            }
            log.info("Speech made: audioBytes={} durationMillis={}", bytes.length,
                    (System.nanoTime() - start) / 1_000_000);
            return new SpeechAudio(bytes, mime);
        } catch (AiException e) {
            log.warn("Speech synthesis failed: kind={}", e.kind());
            throw e;
        } catch (IllegalArgumentException e) {
            log.warn("Speech synthesis failed: kind={}", Kind.UNREACHABLE);
            throw new AiException(Kind.UNREACHABLE, "Speech server could not be reached", e);
        }
    }

    @Override
    public boolean isAvailable() {
        return VoiceHttp.isListening(http, props.baseUrl());
    }

    private String requestBody(String text, String languageTag, String voice) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", props.model());
        body.put("input", text);
        body.put("voice", chooseVoice(languageTag, voice));
        body.put("response_format", props.format());
        return mapper.writeValueAsString(body);
    }

    /** The given voice, else the default of the language: "it" speaks with the Italian voice, anything else English. */
    private String chooseVoice(String languageTag, String voice) {
        if (voice != null && !voice.isBlank()) {
            return voice;
        }
        return "it".equals(VoiceHttp.primaryLanguage(languageTag)) ? props.voiceIt() : props.voiceEn();
    }

    /** The MIME type of an audio format name, used when the engine sends no Content-Type. */
    static String mimeForFormat(String format) {
        String f = format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
        return switch (f) {
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "opus" -> "audio/ogg";
            case "aac" -> "audio/aac";
            case "flac" -> "audio/flac";
            default -> "application/octet-stream";
        };
    }
}
