package it.ristomanager.cloud.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.ristomanager.cloud.ai.AiException;
import it.ristomanager.cloud.ai.AiException.Kind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleRecognizerTest {

    private static final byte[] AUDIO = "FAKE-AUDIO-BYTES".getBytes(StandardCharsets.US_ASCII);
    private static final String PATH = "/v1/audio/transcriptions";

    private FakeEngine engine;

    @AfterEach
    void stop() {
        if (engine != null) {
            engine.close();
        }
    }

    // ---- a tiny multipart reader, enough to check what was really sent

    private record Part(String name, String filename, String contentType, byte[] content) {
        String text() {
            return new String(content, StandardCharsets.UTF_8);
        }
    }

    private static List<Part> parts(String contentTypeHeader, byte[] body) {
        Matcher boundary = Pattern.compile("boundary=(\\S+)").matcher(contentTypeHeader);
        assertThat(boundary.find()).as("boundary in the Content-Type header").isTrue();
        String delimiter = "--" + boundary.group(1);
        // ISO-8859-1 maps every byte to one char, so binary content survives the round trip.
        String raw = new String(body, StandardCharsets.ISO_8859_1);
        String[] chunks = raw.split(Pattern.quote(delimiter));
        assertThat(chunks[chunks.length - 1]).as("closing delimiter").startsWith("--");
        List<Part> result = new ArrayList<>();
        for (int i = 1; i < chunks.length - 1; i++) {
            String chunk = chunks[i];
            assertThat(chunk).startsWith("\r\n").endsWith("\r\n");
            int split = chunk.indexOf("\r\n\r\n");
            String headers = chunk.substring(2, split);
            String content = chunk.substring(split + 4, chunk.length() - 2);
            Matcher name = Pattern.compile("name=\"([^\"]*)\"").matcher(headers);
            assertThat(name.find()).isTrue();
            Matcher filename = Pattern.compile("filename=\"([^\"]*)\"").matcher(headers);
            Matcher type = Pattern.compile("(?im)^Content-Type: (.*)$").matcher(headers);
            result.add(new Part(name.group(1), filename.find() ? filename.group(1) : null,
                    type.find() ? type.group(1).trim() : null, content.getBytes(StandardCharsets.ISO_8859_1)));
        }
        return result;
    }

    private List<Part> sentParts() {
        FakeEngine.Seen request = engine.last();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo(PATH);
        assertThat(request.contentType()).startsWith("multipart/form-data; boundary=");
        return parts(request.contentType(), request.body());
    }

    private static Map<String, Part> byName(List<Part> parts) {
        Map<String, Part> map = new LinkedHashMap<>();
        parts.forEach(p -> map.put(p.name(), p));
        return map;
    }

    private void start(int status, String body) throws IOException {
        engine = new FakeEngine().answer(PATH, status, "application/json", body.getBytes(StandardCharsets.UTF_8), 0);
    }

    private static OpenAiCompatibleRecognizer recognizer(String baseUrl) {
        return recognizer(baseUrl, null, Duration.ofSeconds(5));
    }

    private static OpenAiCompatibleRecognizer recognizer(String baseUrl, String language, Duration timeout) {
        return new OpenAiCompatibleRecognizer(new VoiceProperties.Stt(baseUrl, "whisper-1", language, timeout));
    }

    private static void assertKind(Throwable e, Kind kind) {
        assertThat(e).isInstanceOfSatisfying(AiException.class, ai -> assertThat(ai.kind()).isEqualTo(kind));
    }

    // ---- request shape

    @Test
    void sendsTheAudioAndSettingsAsMultipartParts() throws Exception {
        start(200, "{\"text\":\"ciao\"}");

        recognizer(engine.baseUrl()).transcribe(AUDIO, "audio/webm", "it");

        List<Part> parts = sentParts();
        assertThat(parts).extracting(Part::name).containsExactly("file", "model", "response_format", "language");
        Map<String, Part> byName = byName(parts);
        assertThat(byName.get("file").filename()).isEqualTo("audio.webm");
        assertThat(byName.get("file").contentType()).isEqualTo("audio/webm");
        assertThat(byName.get("file").content()).isEqualTo(AUDIO);
        assertThat(byName.get("model").text()).isEqualTo("whisper-1");
        assertThat(byName.get("response_format").text()).isEqualTo("json");
        assertThat(byName.get("language").text()).isEqualTo("it");
    }

    @Test
    void theBoundaryIsRandomPerRequest() throws Exception {
        start(200, "{\"text\":\"ciao\"}");
        OpenAiCompatibleRecognizer recognizer = recognizer(engine.baseUrl());

        recognizer.transcribe(AUDIO, "audio/webm", null);
        String first = engine.last().contentType();
        recognizer.transcribe(AUDIO, "audio/webm", null);
        String second = engine.last().contentType();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void theFileNameExtensionMatchesTheMimeType() throws Exception {
        start(200, "{\"text\":\"ciao\"}");
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("audio/webm", "audio.webm");
        expected.put("audio/ogg", "audio.ogg");
        expected.put("audio/mp4", "audio.mp4");
        expected.put("audio/x-m4a", "audio.m4a");
        expected.put("audio/m4a", "audio.m4a");
        expected.put("audio/aac", "audio.aac");
        expected.put("audio/wav", "audio.wav");
        expected.put("audio/x-wav", "audio.wav");
        expected.put("audio/mpeg", "audio.mp3");
        expected.put("audio/mp3", "audio.mp3");
        OpenAiCompatibleRecognizer recognizer = recognizer(engine.baseUrl());

        for (Map.Entry<String, String> entry : expected.entrySet()) {
            recognizer.transcribe(AUDIO, entry.getKey(), null);
            assertThat(byName(sentParts()).get("file").filename()).as(entry.getKey()).isEqualTo(entry.getValue());
        }
    }

    @Test
    void mimeTypeParametersAreDroppedFromThePart() throws Exception {
        start(200, "{\"text\":\"ciao\"}");

        recognizer(engine.baseUrl()).transcribe(AUDIO, "audio/webm;codecs=opus", null);

        Part file = byName(sentParts()).get("file");
        assertThat(file.filename()).isEqualTo("audio.webm");
        assertThat(file.contentType()).isEqualTo("audio/webm");
    }

    @Test
    void theLanguagePartIsSentOnlyWhenALanguageIsKnown() throws Exception {
        start(200, "{\"text\":\"ciao\"}");

        recognizer(engine.baseUrl()).transcribe(AUDIO, "audio/webm", null);
        assertThat(byName(sentParts())).doesNotContainKey("language");

        recognizer(engine.baseUrl()).transcribe(AUDIO, "audio/webm", "  ");
        assertThat(byName(sentParts())).doesNotContainKey("language");

        recognizer(engine.baseUrl()).transcribe(AUDIO, "audio/webm", "en");
        assertThat(byName(sentParts()).get("language").text()).isEqualTo("en");

        // A region tag is reduced to the language the engines understand.
        recognizer(engine.baseUrl()).transcribe(AUDIO, "audio/webm", "it-IT");
        assertThat(byName(sentParts()).get("language").text()).isEqualTo("it");
    }

    @Test
    void theConfiguredLanguageIsTheFallbackAndTheArgumentWins() throws Exception {
        start(200, "{\"text\":\"ciao\"}");
        OpenAiCompatibleRecognizer forcedItalian = recognizer(engine.baseUrl(), "it", Duration.ofSeconds(5));

        forcedItalian.transcribe(AUDIO, "audio/webm", null);
        assertThat(byName(sentParts()).get("language").text()).isEqualTo("it");

        forcedItalian.transcribe(AUDIO, "audio/webm", "en");
        assertThat(byName(sentParts()).get("language").text()).isEqualTo("en");

        // An empty setting (the environment variable left empty) means auto-detect.
        recognizer(engine.baseUrl(), "", Duration.ofSeconds(5)).transcribe(AUDIO, "audio/webm", null);
        assertThat(byName(sentParts())).doesNotContainKey("language");
    }

    @Test
    void aBaseUrlWithATrailingSlashIsAccepted() throws Exception {
        start(200, "{\"text\":\"ciao\"}");

        recognizer(engine.baseUrl() + "/").transcribe(AUDIO, "audio/webm", null);

        assertThat(engine.last().path()).isEqualTo(PATH);
    }

    // ---- results

    @Test
    void returnsTheTranscript() throws Exception {
        start(200, "{\"text\":\"Un tavolo per due, per favore\"}");

        assertThat(recognizer(engine.baseUrl()).transcribe(AUDIO, "audio/webm", "it"))
                .isEqualTo("Un tavolo per due, per favore");
    }

    @Test
    void extraFieldsInTheAnswerAreIgnored() throws Exception {
        start(200, "{\"text\":\"ciao\",\"language\":\"it\",\"duration\":1.5}");

        assertThat(recognizer(engine.baseUrl()).transcribe(AUDIO, "audio/webm", null)).isEqualTo("ciao");
    }

    @Test
    void aBlankTranscriptIsSilenceNotAnError() throws Exception {
        start(200, "{\"text\":\"\"}");
        assertThat(recognizer(engine.baseUrl()).transcribe(AUDIO, "audio/webm", null)).isEmpty();
        engine.close();

        start(200, "{\"text\":\"   \"}");
        assertThat(recognizer(engine.baseUrl()).transcribe(AUDIO, "audio/webm", null)).isEmpty();
    }

    // ---- failures

    @Test
    void aSlowEngineIsATimeout() throws Exception {
        engine = new FakeEngine().answer(PATH, 200, "application/json", "{\"text\":\"ciao\"}".getBytes(), 1500);

        assertThatThrownBy(() -> recognizer(engine.baseUrl(), null, Duration.ofMillis(300))
                .transcribe(AUDIO, "audio/webm", null))
                .satisfies(e -> assertKind(e, Kind.TIMEOUT));
    }

    @Test
    void anHttpErrorIsABadResponseThatHoldsOnlyTheStatusCode() throws Exception {
        start(500, "SECRET-ENGINE-BODY");

        assertThatThrownBy(() -> recognizer(engine.baseUrl()).transcribe(AUDIO, "audio/webm", null))
                .satisfies(e -> assertKind(e, Kind.BAD_RESPONSE))
                .hasMessageContaining("500")
                .hasMessageNotContaining("SECRET-ENGINE-BODY");
    }

    @Test
    void aClientErrorIsABadResponseToo() throws Exception {
        start(400, "{\"error\":\"unsupported file SECRET-DETAIL\"}");

        assertThatThrownBy(() -> recognizer(engine.baseUrl()).transcribe(AUDIO, "audio/webm", null))
                .satisfies(e -> assertKind(e, Kind.BAD_RESPONSE))
                .hasMessageContaining("400")
                .hasMessageNotContaining("SECRET-DETAIL");
    }

    @Test
    void invalidJsonIsABadResponseAndTheBodyIsNotEchoed() throws Exception {
        start(200, "this is not json SECRET-TRANSCRIPT {");

        assertThatThrownBy(() -> recognizer(engine.baseUrl()).transcribe(AUDIO, "audio/webm", null))
                .satisfies(e -> assertKind(e, Kind.BAD_RESPONSE))
                .hasMessageNotContaining("SECRET-TRANSCRIPT");
    }

    @Test
    void anAnswerWithoutATextStringIsABadResponse() throws Exception {
        for (String body : new String[] {"{}", "{\"text\":42}", "{\"text\":{\"a\":1}}", "{\"text\":null}", "[]", "", "42"}) {
            start(200, body);
            assertThatThrownBy(() -> recognizer(engine.baseUrl()).transcribe(AUDIO, "audio/webm", null))
                    .as("body: " + body)
                    .satisfies(e -> assertKind(e, Kind.BAD_RESPONSE));
            engine.close();
        }
    }

    @Test
    void nothingListeningIsUnreachableAndTheMessageHasNoAddress() throws Exception {
        String dead = FakeEngine.deadUrl();

        assertThatThrownBy(() -> recognizer(dead).transcribe(AUDIO, "audio/webm", null))
                .satisfies(e -> assertKind(e, Kind.UNREACHABLE))
                .hasMessageNotContaining("127.0.0.1");
    }

    @Test
    void anUnusableBaseUrlIsUnreachableNotACrash() {
        assertThatThrownBy(() -> recognizer("http://[not-an-address").transcribe(AUDIO, "audio/webm", null))
                .satisfies(e -> assertKind(e, Kind.UNREACHABLE));
    }

    // ---- availability

    @Test
    void availableWhenTheServerAnswersAnythingBelow500() throws Exception {
        for (int status : new int[] {200, 401, 404}) {
            engine = new FakeEngine().answer("/v1/models", status, "application/json", "{}".getBytes(), 0);
            assertThat(recognizer(engine.baseUrl()).isAvailable()).as("HTTP " + status).isTrue();
            assertThat(engine.last().method()).isEqualTo("GET");
            assertThat(engine.last().path()).isEqualTo("/v1/models");
            engine.close();
        }
    }

    @Test
    void anUnknownModelsPathStillCountsAsUp() throws Exception {
        // The fake knows no /v1/models at all: the real JDK server answers 404.
        engine = new FakeEngine();

        assertThat(recognizer(engine.baseUrl()).isAvailable()).isTrue();
    }

    @Test
    void notAvailableWhenTheServerAnswers5xx() throws Exception {
        for (int status : new int[] {500, 503}) {
            engine = new FakeEngine().answer("/v1/models", status, "text/plain", "boom".getBytes(), 0);
            assertThat(recognizer(engine.baseUrl()).isAvailable()).as("HTTP " + status).isFalse();
            engine.close();
        }
    }

    @Test
    void notAvailableWhenNothingListens() throws Exception {
        assertThat(recognizer(FakeEngine.deadUrl()).isAvailable()).isFalse();
    }

    @Test
    void notAvailableWhenTheServerAnswersTooSlowly() throws Exception {
        engine = new FakeEngine().answer("/v1/models", 200, "application/json", "{}".getBytes(), 3000);

        long start = System.nanoTime();
        assertThat(recognizer(engine.baseUrl()).isAvailable()).isFalse();

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(2900));
    }

    @Test
    void isAvailableNeverThrows() {
        assertThatCode(() -> recognizer("http://[not-an-address").isAvailable()).doesNotThrowAnyException();
        assertThatCode(() -> recognizer("not a url at all").isAvailable()).doesNotThrowAnyException();
        assertThat(recognizer("http://[not-an-address").isAvailable()).isFalse();
    }
}
