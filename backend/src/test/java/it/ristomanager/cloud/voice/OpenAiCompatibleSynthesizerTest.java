package it.ristomanager.cloud.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.ristomanager.cloud.ai.AiException;
import it.ristomanager.cloud.ai.AiException.Kind;
import it.ristomanager.cloud.ai.SpeechAudio;
import it.ristomanager.cloud.common.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OpenAiCompatibleSynthesizerTest {

    private static final byte[] AUDIO = "FAKE-MP3-BYTES".getBytes(StandardCharsets.US_ASCII);
    private static final String PATH = "/v1/audio/speech";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private FakeEngine engine;

    @AfterEach
    void stop() {
        if (engine != null) {
            engine.close();
        }
    }

    private void start(int status, String contentType, byte[] body) throws IOException {
        engine = new FakeEngine().answer(PATH, status, contentType, body, 0);
    }

    private static OpenAiCompatibleSynthesizer synthesizer(String baseUrl) {
        return synthesizer(baseUrl, "mp3", Duration.ofSeconds(5));
    }

    private static OpenAiCompatibleSynthesizer synthesizer(String baseUrl, String format, Duration timeout) {
        return new OpenAiCompatibleSynthesizer(
                new VoiceProperties.Tts(baseUrl, "kokoro", "if_sara", "af_heart", format, timeout));
    }

    private JsonNode sentBody() {
        FakeEngine.Seen request = engine.last();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo(PATH);
        assertThat(request.contentType()).startsWith("application/json");
        return JSON.readTree(new String(request.body(), StandardCharsets.UTF_8));
    }

    private static void assertKind(Throwable e, Kind kind) {
        assertThat(e).isInstanceOfSatisfying(AiException.class, ai -> assertThat(ai.kind()).isEqualTo(kind));
    }

    // ---- request shape

    @Test
    void sendsAJsonBodyWithModelInputVoiceAndFormat() throws Exception {
        start(200, "audio/mpeg", AUDIO);

        synthesizer(engine.baseUrl()).speak("Il tavolo e pronto", "it", null);

        JsonNode body = sentBody();
        assertThat(Json.text(body.path("model"))).isEqualTo("kokoro");
        assertThat(Json.text(body.path("input"))).isEqualTo("Il tavolo e pronto");
        assertThat(Json.text(body.path("voice"))).isEqualTo("if_sara");
        assertThat(Json.text(body.path("response_format"))).isEqualTo("mp3");
        assertThat(body.size()).isEqualTo(4);
    }

    @Test
    void theTextIsEscapedByTheJsonMapperAndArrivesUnchanged() throws Exception {
        start(200, "audio/mpeg", AUDIO);
        String tricky = "He said \"hi\" \\ and left\nnew line, caffè è 🍕 </script>";

        synthesizer(engine.baseUrl()).speak(tricky, "en", null);

        assertThat(Json.text(sentBody().path("input"))).isEqualTo(tricky);
    }

    @Test
    void theVoiceFollowsTheLanguageUnlessOneIsGiven() throws Exception {
        start(200, "audio/mpeg", AUDIO);
        OpenAiCompatibleSynthesizer synthesizer = synthesizer(engine.baseUrl());
        Map<String, String> voiceFor = new LinkedHashMap<>();
        voiceFor.put("it", "if_sara");
        voiceFor.put("it-IT", "if_sara");
        voiceFor.put("IT", "if_sara");
        voiceFor.put("en", "af_heart");
        voiceFor.put("en-GB", "af_heart");
        voiceFor.put("fr", "af_heart");
        voiceFor.put(null, "af_heart");
        voiceFor.put("", "af_heart");

        for (Map.Entry<String, String> entry : voiceFor.entrySet()) {
            synthesizer.speak("Ciao", entry.getKey(), null);
            assertThat(Json.text(sentBody().path("voice"))).as("language " + entry.getKey()).isEqualTo(entry.getValue());
        }

        synthesizer.speak("Ciao", "it", "im_nicola");
        assertThat(Json.text(sentBody().path("voice"))).isEqualTo("im_nicola");
        synthesizer.speak("Ciao", "en", "im_nicola");
        assertThat(Json.text(sentBody().path("voice"))).isEqualTo("im_nicola");
        // A blank voice means "no voice given".
        synthesizer.speak("Ciao", "it", "  ");
        assertThat(Json.text(sentBody().path("voice"))).isEqualTo("if_sara");
    }

    @Test
    void theConfiguredFormatIsRequested() throws Exception {
        start(200, "audio/wav", AUDIO);

        synthesizer(engine.baseUrl(), "wav", Duration.ofSeconds(5)).speak("Ciao", "it", null);

        assertThat(Json.text(sentBody().path("response_format"))).isEqualTo("wav");
    }

    @Test
    void aBaseUrlWithATrailingSlashIsAccepted() throws Exception {
        start(200, "audio/mpeg", AUDIO);

        synthesizer(engine.baseUrl() + "/").speak("Ciao", "it", null);

        assertThat(engine.last().path()).isEqualTo(PATH);
    }

    // ---- results

    @Test
    void returnsTheBytesAndTheMimeTypeOfTheAnswer() throws Exception {
        start(200, "audio/mpeg", AUDIO);

        SpeechAudio audio = synthesizer(engine.baseUrl()).speak("Ciao", "it", null);

        assertThat(audio.bytes()).isEqualTo(AUDIO);
        assertThat(audio.mimeType()).isEqualTo("audio/mpeg");
    }

    @Test
    void theMimeTypeOfTheAnswerWinsOverTheFormatAndLosesItsParameters() throws Exception {
        start(200, "Audio/OGG; codecs=opus", AUDIO);

        SpeechAudio audio = synthesizer(engine.baseUrl(), "mp3", Duration.ofSeconds(5)).speak("Ciao", "it", null);

        assertThat(audio.mimeType()).isEqualTo("audio/ogg");
    }

    @Test
    void withoutAContentTypeTheMimeTypeComesFromTheFormat() throws Exception {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("mp3", "audio/mpeg");
        expected.put("wav", "audio/wav");
        expected.put("opus", "audio/ogg");
        expected.put("aac", "audio/aac");
        expected.put("flac", "audio/flac");
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            start(200, null, AUDIO);

            SpeechAudio audio = synthesizer(engine.baseUrl(), entry.getKey(), Duration.ofSeconds(5))
                    .speak("Ciao", "it", null);

            assertThat(audio.mimeType()).as(entry.getKey()).isEqualTo(entry.getValue());
            engine.close();
        }
    }

    @Test
    void audioExactlyAtTheSizeLimitIsAccepted() throws Exception {
        start(200, "audio/mpeg", new byte[OpenAiCompatibleSynthesizer.MAX_AUDIO_BYTES]);

        SpeechAudio audio = synthesizer(engine.baseUrl()).speak("Ciao", "it", null);

        assertThat(audio.bytes()).hasSize(10 * 1024 * 1024);
    }

    // ---- failures

    @Test
    void anEmptyAudioBodyIsABadResponse() throws Exception {
        start(200, "audio/mpeg", new byte[0]);

        assertThatThrownBy(() -> synthesizer(engine.baseUrl()).speak("Ciao", "it", null))
                .satisfies(e -> assertKind(e, Kind.BAD_RESPONSE));
    }

    @Test
    void audioOverTenMegabytesIsABadResponse() throws Exception {
        start(200, "audio/mpeg", new byte[OpenAiCompatibleSynthesizer.MAX_AUDIO_BYTES + 1]);

        assertThatThrownBy(() -> synthesizer(engine.baseUrl()).speak("Ciao", "it", null))
                .satisfies(e -> assertKind(e, Kind.BAD_RESPONSE));
    }

    @Test
    void aSlowEngineIsATimeout() throws Exception {
        engine = new FakeEngine().answer(PATH, 200, "audio/mpeg", AUDIO, 1500);

        assertThatThrownBy(() -> synthesizer(engine.baseUrl(), "mp3", Duration.ofMillis(300)).speak("Ciao", "it", null))
                .satisfies(e -> assertKind(e, Kind.TIMEOUT));
    }

    @Test
    void anHttpErrorIsABadResponseThatHoldsOnlyTheStatusCode() throws Exception {
        start(500, "application/json", "SECRET-ENGINE-BODY about Il tavolo".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> synthesizer(engine.baseUrl()).speak("Il tavolo e pronto", "it", null))
                .satisfies(e -> assertKind(e, Kind.BAD_RESPONSE))
                .hasMessageContaining("500")
                .hasMessageNotContaining("SECRET-ENGINE-BODY")
                .hasMessageNotContaining("Il tavolo");
    }

    @Test
    void aClientErrorIsABadResponseToo() throws Exception {
        start(422, "application/json", "{\"detail\":\"bad voice SECRET-DETAIL\"}".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> synthesizer(engine.baseUrl()).speak("Ciao", "it", null))
                .satisfies(e -> assertKind(e, Kind.BAD_RESPONSE))
                .hasMessageContaining("422")
                .hasMessageNotContaining("SECRET-DETAIL");
    }

    @Test
    void nothingListeningIsUnreachableAndTheMessageHasNeitherAddressNorText() throws Exception {
        String dead = FakeEngine.deadUrl();

        assertThatThrownBy(() -> synthesizer(dead).speak("Il tavolo e pronto", "it", null))
                .satisfies(e -> assertKind(e, Kind.UNREACHABLE))
                .hasMessageNotContaining("127.0.0.1")
                .hasMessageNotContaining("Il tavolo");
    }

    @Test
    void anUnusableBaseUrlIsUnreachableNotACrash() {
        assertThatThrownBy(() -> synthesizer("http://[not-an-address").speak("Ciao", "it", null))
                .satisfies(e -> assertKind(e, Kind.UNREACHABLE));
    }

    // ---- availability

    @Test
    void availableWhenTheServerAnswersAnythingBelow500() throws Exception {
        for (int status : new int[] {200, 401, 404}) {
            engine = new FakeEngine().answer("/v1/models", status, "application/json", "{}".getBytes(), 0);
            assertThat(synthesizer(engine.baseUrl()).isAvailable()).as("HTTP " + status).isTrue();
            assertThat(engine.last().method()).isEqualTo("GET");
            assertThat(engine.last().path()).isEqualTo("/v1/models");
            engine.close();
        }
    }

    @Test
    void anUnknownModelsPathStillCountsAsUp() throws Exception {
        // Kokoro-FastAPI documents no models or health path: the server answers 404 and is still up.
        engine = new FakeEngine();

        assertThat(synthesizer(engine.baseUrl()).isAvailable()).isTrue();
    }

    @Test
    void notAvailableWhenTheServerAnswers5xx() throws Exception {
        for (int status : new int[] {500, 503}) {
            engine = new FakeEngine().answer("/v1/models", status, "text/plain", "boom".getBytes(), 0);
            assertThat(synthesizer(engine.baseUrl()).isAvailable()).as("HTTP " + status).isFalse();
            engine.close();
        }
    }

    @Test
    void notAvailableWhenNothingListens() throws Exception {
        assertThat(synthesizer(FakeEngine.deadUrl()).isAvailable()).isFalse();
    }

    @Test
    void notAvailableWhenTheServerAnswersTooSlowly() throws Exception {
        engine = new FakeEngine().answer("/v1/models", 200, "application/json", "{}".getBytes(), 3000);

        long start = System.nanoTime();
        assertThat(synthesizer(engine.baseUrl()).isAvailable()).isFalse();

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(2900));
    }

    @Test
    void isAvailableNeverThrows() {
        assertThatCode(() -> synthesizer("http://[not-an-address").isAvailable()).doesNotThrowAnyException();
        assertThatCode(() -> synthesizer("not a url at all").isAvailable()).doesNotThrowAnyException();
        assertThat(synthesizer("http://[not-an-address").isAvailable()).isFalse();
    }
}
