package it.ristomanager.cloud.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.ristomanager.cloud.ai.AiException;
import it.ristomanager.cloud.ai.AiException.Kind;
import it.ristomanager.cloud.ai.LlmReply;
import it.ristomanager.cloud.ai.SpeechAudio;
import it.ristomanager.cloud.support.ApiClient;
import it.ristomanager.cloud.support.ApiClient.Actor;
import it.ristomanager.cloud.support.PostgresTestConfig;
import it.ristomanager.cloud.support.ScriptedLlm;
import it.ristomanager.cloud.support.ScriptedLlmConfig;
import it.ristomanager.cloud.support.ScriptedSpeech;
import it.ristomanager.cloud.support.ScriptedSpeechConfig;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Voice through the real REST endpoints and a real database. The language model and both speech engines are replaced by
 * scripted fakes, so the tests decide what the engines answer. Voice is switched on here, with a small recording limit
 * and a rate limit of three calls a minute per person (every test uses a fresh owner, so each has its own budget).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfig.class, ScriptedLlmConfig.class, ScriptedSpeechConfig.class})
@TestPropertySource(properties = {
        "app.voice.enabled=true",
        "app.voice.limits.max-audio-bytes=2048",
        "app.voice.limits.per-user-per-minute=3"})
class VoiceIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final byte[] RECORDING = "RECORDED-SOUND".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SPOKEN = "SPOKEN-SOUND".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    MockMvc mvc;

    @Autowired
    ScriptedLlm llm;

    @Autowired
    ScriptedSpeech speech;

    ApiClient api;
    Actor owner;

    @BeforeEach
    void setUp() throws Exception {
        llm.reset();
        speech.reset();
        api = new ApiClient(mvc);
        owner = api.signup("R");
    }

    // ---- helpers

    /** POSTs a recording; a null actor sends no token, a null language leaves the parameter out. */
    private ResultActions transcribe(Actor who, byte[] audio, String contentType, String language) throws Exception {
        var request = multipart("/api/v1/voice/transcribe")
                .file(new MockMultipartFile("audio", "recording", contentType, audio));
        if (language != null) {
            request.param("language", language);
        }
        if (who != null) {
            request.header("Authorization", "Bearer " + who.token());
        }
        return mvc.perform(request);
    }

    private ResultActions transcribe(Actor who, String language) throws Exception {
        return transcribe(who, RECORDING, "audio/webm", language);
    }

    private static String speakBody(String text, String language, String voice) {
        ObjectNode body = JSON.createObjectNode();
        if (text != null) {
            body.put("text", text);
        }
        if (language != null) {
            body.put("language", language);
        }
        if (voice != null) {
            body.put("voice", voice);
        }
        return JSON.writeValueAsString(body);
    }

    private ResultActions speak(Actor who, String text, String language, String voice) throws Exception {
        return api.post(who, "/api/v1/voice/speak", speakBody(text, language, voice));
    }

    private static SpeechAudio audio() {
        return new SpeechAudio(SPOKEN, "audio/mpeg");
    }

    private static LlmReply say(String text) {
        return new LlmReply(text, "scripted", 0, 0, 0);
    }

    // ---- status

    @Test
    void everyRoleSeesTheStatus() throws Exception {
        Actor manager = api.staff(owner, "MANAGER");
        Actor waiter = api.staff(owner, "WAITER");
        Actor kitchen = api.staff(owner, "KITCHEN");

        for (Actor who : new Actor[] {owner, manager, waiter, kitchen}) {
            api.get(who, "/api/v1/voice/status").andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled").value(true))
                    .andExpect(jsonPath("$.speechToText").value(true))
                    .andExpect(jsonPath("$.textToSpeech").value(true));
        }
    }

    @Test
    void theStatusReportsEachEngineOnItsOwn() throws Exception {
        speech.listeningAvailable = false;
        api.get(owner, "/api/v1/voice/status").andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.speechToText").value(false))
                .andExpect(jsonPath("$.textToSpeech").value(true));

        speech.listeningAvailable = true;
        speech.speakingAvailable = false;
        api.get(owner, "/api/v1/voice/status").andExpect(status().isOk())
                .andExpect(jsonPath("$.speechToText").value(true))
                .andExpect(jsonPath("$.textToSpeech").value(false));

        speech.listeningAvailable = false;
        api.get(owner, "/api/v1/voice/status").andExpect(status().isOk())
                .andExpect(jsonPath("$.speechToText").value(false))
                .andExpect(jsonPath("$.textToSpeech").value(false));
    }

    @Test
    void withoutALoginEveryVoiceEndpointIsRefused() throws Exception {
        mvc.perform(get("/api/v1/voice/status")).andExpect(status().isUnauthorized());
        transcribe(null, "it").andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/voice/speak").contentType(MediaType.APPLICATION_JSON)
                .content(speakBody("Ciao", "it", null))).andExpect(status().isUnauthorized());

        assertThat(speech.heardCalls()).isZero();
        assertThat(speech.spokenCalls()).isZero();
    }

    // ---- transcribe

    @Test
    void aRecordingBecomesTextAndTheEngineGetsTheAudioAndTheLanguage() throws Exception {
        speech.transcripts.add("Un tavolo per due");

        transcribe(owner, "it").andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("Un tavolo per due"));

        assertThat(speech.heardCalls()).isEqualTo(1);
        ScriptedSpeech.Heard heard = speech.heard.get(0);
        assertThat(heard.audio()).isEqualTo(RECORDING);
        assertThat(heard.mimeType()).isEqualTo("audio/webm");
        assertThat(heard.languageTag()).isEqualTo("it");
        // The assistant is a separate thing: listening never reaches the language model.
        assertThat(llm.calls()).isZero();
    }

    @Test
    void withoutALanguageTheEngineDetectsIt() throws Exception {
        speech.transcripts.add("Table for two");

        transcribe(owner, null).andExpect(status().isOk()).andExpect(jsonPath("$.text").value("Table for two"));

        assertThat(speech.heard.get(0).languageTag()).isNull();
    }

    @Test
    void englishIsAcceptedToo() throws Exception {
        speech.transcripts.add("Table for two");

        transcribe(owner, "en").andExpect(status().isOk());

        assertThat(speech.heard.get(0).languageTag()).isEqualTo("en");
    }

    @Test
    void silenceIsAnEmptyTextNotAnError() throws Exception {
        speech.transcripts.add("");

        transcribe(owner, "it").andExpect(status().isOk()).andExpect(jsonPath("$.text").value(""));
    }

    @Test
    void theTranscriptIsCleanedAndCut() throws Exception {
        speech.transcripts.add("\n  Un tavolo\nper due\u0007\t ");
        transcribe(owner, "it").andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("Un tavolo per due"));

        speech.transcripts.add("a".repeat(1500));
        String text = ApiClient.read(transcribe(owner, "it").andExpect(status().isOk()), "$.text");
        assertThat(text).hasSize(1000).matches("a+");
    }

    @Test
    void aContentTypeWithParametersIsAcceptedAndPassedWithoutThem() throws Exception {
        speech.transcripts.add("ciao");

        transcribe(owner, RECORDING, "audio/webm;codecs=opus", "it").andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("ciao"));

        assertThat(speech.heard.get(0).mimeType()).isEqualTo("audio/webm");
    }

    @Test
    void everyListedAudioTypeIsAccepted() throws Exception {
        String[] types = {"audio/webm", "audio/ogg", "audio/mp4", "audio/x-m4a", "audio/m4a", "audio/aac", "audio/wav",
                "audio/x-wav", "audio/mpeg", "audio/mp3"};
        // The rate limit is three a minute per person, so each type is sent by a person of its own.
        for (String type : types) {
            Actor who = api.signup("R-" + type.substring(6));
            speech.transcripts.add("ok");
            transcribe(who, RECORDING, type, null).andExpect(status().isOk());
        }
        assertThat(speech.heardCalls()).isEqualTo(types.length);
    }

    @Test
    void anEmptyRecordingIsRefusedBeforeTheEngineIsCalled() throws Exception {
        transcribe(owner, new byte[0], "audio/webm", "it").andExpect(status().isBadRequest());

        assertThat(speech.heardCalls()).isZero();
    }

    @Test
    void aMissingAudioPartIsRefused() throws Exception {
        mvc.perform(multipart("/api/v1/voice/transcribe").header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isBadRequest());

        assertThat(speech.heardCalls()).isZero();
    }

    @Test
    void aRecordingOverTheConfiguredLimitIsTooLarge() throws Exception {
        // The limit is 2048 bytes in this test class (app.voice.limits.max-audio-bytes).
        transcribe(owner, new byte[2049], "audio/webm", "it").andExpect(status().is(413))
                .andExpect(jsonPath("$.detail").value("The recording is too long"));
        assertThat(speech.heardCalls()).isZero();

        speech.transcripts.add("ok");
        transcribe(owner, new byte[2048], "audio/webm", "it").andExpect(status().isOk());
        assertThat(speech.heardCalls()).isEqualTo(1);
    }

    @Test
    void anAudioTypeThatIsNotListedIsRefused() throws Exception {
        String[] types = {"text/plain", "application/octet-stream", "video/webm", "image/png", "audio/flac", "audio"};
        for (String type : types) {
            transcribe(owner, RECORDING, type, "it").andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.detail").value("Unsupported audio format"));
        }
        // A part with no content type at all.
        transcribe(owner, RECORDING, null, "it").andExpect(status().isUnsupportedMediaType());

        assertThat(speech.heardCalls()).isZero();
    }

    @Test
    void aLanguageOtherThanItOrEnIsRefused() throws Exception {
        for (String language : new String[] {"fr", "IT", "it-IT", "", "xx"}) {
            transcribe(owner, language).andExpect(status().isBadRequest());
        }

        assertThat(speech.heardCalls()).isZero();
    }

    @Test
    void failuresOfTheListeningEngineAreReportedWithTheRightStatus() throws Exception {
        speech.transcripts.add(new AiException(Kind.UNREACHABLE, "down"));
        transcribe(owner, "it").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("Voice is not available"));

        speech.transcripts.add(new AiException(Kind.TIMEOUT, "slow"));
        transcribe(owner, "it").andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.detail").value("Voice took too long"));

        speech.transcripts.add(new AiException(Kind.BAD_RESPONSE, "garbage"));
        transcribe(owner, "it").andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("Voice gave an unusable answer"));
    }

    @Test
    void aDisabledEngineIsReportedAsNotAvailable() throws Exception {
        speech.transcripts.add(new AiException(Kind.DISABLED, "off"));
        transcribe(owner, "it").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("Voice is not available"));

        speech.speech.add(new AiException(Kind.DISABLED, "off"));
        speak(owner, "Ciao", "it", null).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("Voice is not available"));
    }

    // ---- speak

    @Test
    void textBecomesAudioWithItsContentTypeAndNothingIsCached() throws Exception {
        speech.speech.add(audio());

        byte[] body = speak(owner, "Il tavolo sei e pronto", "it", "if_sara").andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "audio/mpeg"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(body).isEqualTo(SPOKEN);
        assertThat(speech.spokenCalls()).isEqualTo(1);
        ScriptedSpeech.Spoken spoken = speech.spoken.get(0);
        assertThat(spoken.text()).isEqualTo("Il tavolo sei e pronto");
        assertThat(spoken.languageTag()).isEqualTo("it");
        assertThat(spoken.voice()).isEqualTo("if_sara");
        assertThat(llm.calls()).isZero();
    }

    @Test
    void theContentTypeOfTheEngineIsPassedOn() throws Exception {
        speech.speech.add(new SpeechAudio(SPOKEN, "audio/wav"));

        speak(owner, "Ciao", "it", null).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "audio/wav"));
    }

    @Test
    void languageAndVoiceAreOptionalAndControlCharactersBecomeSpaces() throws Exception {
        speech.speech.add(audio());

        speak(owner, "  Ciao\nmondo\t ", null, null).andExpect(status().isOk());

        ScriptedSpeech.Spoken spoken = speech.spoken.get(0);
        assertThat(spoken.text()).isEqualTo("Ciao mondo");
        assertThat(spoken.languageTag()).isNull();
        assertThat(spoken.voice()).isNull();
    }

    @Test
    void englishIsAcceptedAndTextAtTheLimitIsAccepted() throws Exception {
        speech.speech.add(audio());

        speak(owner, "a".repeat(600), "en", "af_heart").andExpect(status().isOk());

        assertThat(speech.spoken.get(0).text()).hasSize(600);
        assertThat(speech.spoken.get(0).languageTag()).isEqualTo("en");
    }

    @Test
    void blankOrMissingTextIsRefusedBeforeTheEngineIsCalled() throws Exception {
        speak(owner, "", "it", null).andExpect(status().isBadRequest());
        speak(owner, "   ", "it", null).andExpect(status().isBadRequest());
        speak(owner, "\n\t", "it", null).andExpect(status().isBadRequest());
        speak(owner, null, "it", null).andExpect(status().isBadRequest());

        assertThat(speech.spokenCalls()).isZero();
    }

    @Test
    void textLongerThanTheLimitIsRefusedNotCut() throws Exception {
        // 601 is over app.voice.limits.max-text-chars (600, the default); 2001 is over the request's own size limit.
        speak(owner, "a".repeat(601), "it", null).andExpect(status().isBadRequest());
        speak(owner, "a".repeat(2001), "it", null).andExpect(status().isBadRequest());

        assertThat(speech.spokenCalls()).isZero();
    }

    @Test
    void aVoiceNameWithOddCharactersIsRefused() throws Exception {
        for (String voice : new String[] {"bad voice", "../etc", "if_sara;rm", "a".repeat(41), "", "voceè", "x/y"}) {
            speak(owner, "Ciao", "it", voice).andExpect(status().isBadRequest());
        }

        assertThat(speech.spokenCalls()).isZero();
    }

    @Test
    void aLanguageOtherThanItOrEnIsRefusedForSpeaking() throws Exception {
        for (String language : new String[] {"fr", "IT", "it-IT", ""}) {
            speak(owner, "Ciao", language, null).andExpect(status().isBadRequest());
        }

        assertThat(speech.spokenCalls()).isZero();
    }

    @Test
    void failuresOfTheSpeakingEngineAreReportedWithTheRightStatus() throws Exception {
        speech.speech.add(new AiException(Kind.UNREACHABLE, "down"));
        speak(owner, "Ciao", "it", null).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("Voice is not available"));

        speech.speech.add(new AiException(Kind.TIMEOUT, "slow"));
        speak(owner, "Ciao", "it", null).andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.detail").value("Voice took too long"));

        speech.speech.add(new AiException(Kind.BAD_RESPONSE, "garbage"));
        speak(owner, "Ciao", "it", null).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("Voice gave an unusable answer"));
    }

    @Test
    void anEngineContentTypeThatIsNotAMediaTypeIsAnUnusableAnswer() throws Exception {
        speech.speech.add(new SpeechAudio(SPOKEN, "not a media type"));

        speak(owner, "Ciao", "it", null).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("Voice gave an unusable answer"));
    }

    // ---- roles

    @Test
    void kitchenAndWaiterMayListenAndSpeak() throws Exception {
        Actor waiter = api.staff(owner, "WAITER");
        Actor kitchen = api.staff(owner, "KITCHEN");

        for (Actor who : new Actor[] {waiter, kitchen}) {
            speech.transcripts.add("ok");
            transcribe(who, "it").andExpect(status().isOk());
            speech.speech.add(audio());
            speak(who, "Ciao", "it", null).andExpect(status().isOk());
        }

        assertThat(speech.heardCalls()).isEqualTo(2);
        assertThat(speech.spokenCalls()).isEqualTo(2);
    }

    // ---- limits

    @Test
    void aPersonWhoCallsTooOftenIsSlowedDownAndOthersAreNot() throws Exception {
        // Three calls a minute per person in this test class, shared by listening and speaking.
        speech.transcripts.add("uno");
        transcribe(owner, "it").andExpect(status().isOk());
        speech.speech.add(audio());
        speak(owner, "Ciao", "it", null).andExpect(status().isOk());
        speech.transcripts.add("due");
        transcribe(owner, "it").andExpect(status().isOk());

        speak(owner, "Ciao", "it", null).andExpect(status().isTooManyRequests());
        transcribe(owner, "it").andExpect(status().isTooManyRequests());
        assertThat(speech.heardCalls()).as("the refused calls never reached the engines").isEqualTo(2);
        assertThat(speech.spokenCalls()).isEqualTo(1);

        Actor waiter = api.staff(owner, "WAITER");
        speech.speech.add(audio());
        speak(waiter, "Ciao", "it", null).andExpect(status().isOk());
    }

    @Test
    void refusedRequestsDoNotUseUpTheBudget() throws Exception {
        for (int i = 0; i < 6; i++) {
            transcribe(owner, new byte[0], "audio/webm", "it").andExpect(status().isBadRequest());
            speak(owner, " ", "it", null).andExpect(status().isBadRequest());
        }

        speech.speech.add(audio());
        speak(owner, "Ciao", "it", null).andExpect(status().isOk());
    }

    @Test
    void voiceCallsDoNotUseUpTheAssistantsBudget() throws Exception {
        for (int i = 0; i < 3; i++) {
            speech.speech.add(audio());
            speak(owner, "Ciao", "it", null).andExpect(status().isOk());
        }
        speak(owner, "Ciao", "it", null).andExpect(status().isTooManyRequests());

        // The voice budget is spent; the assistant has its own and still answers.
        llm.script.add(say("Ok."));
        api.post(owner, "/api/v1/assistant/chat", "{\"message\":\"ciao\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("Ok."));
    }

    @Test
    void theAssistantsBudgetIsNotUsedUpByVoiceEither() throws Exception {
        // The other direction: twelve chats (the assistant's limit) do not touch the voice budget.
        for (int i = 0; i < 12; i++) {
            llm.script.add(say("Ok."));
            api.post(owner, "/api/v1/assistant/chat", "{\"message\":\"ciao\"}").andExpect(status().isOk());
        }
        api.post(owner, "/api/v1/assistant/chat", "{\"message\":\"ciao\"}").andExpect(status().isTooManyRequests());

        speech.speech.add(audio());
        speak(owner, "Ciao", "it", null).andExpect(status().isOk());
    }
}
