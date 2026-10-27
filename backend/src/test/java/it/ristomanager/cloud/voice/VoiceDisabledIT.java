package it.ristomanager.cloud.voice;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import it.ristomanager.cloud.ai.NoSpeechRecognizer;
import it.ristomanager.cloud.ai.NoSpeechSynthesizer;
import it.ristomanager.cloud.ai.SpeechRecognizer;
import it.ristomanager.cloud.ai.SpeechSynthesizer;
import it.ristomanager.cloud.support.ApiClient;
import it.ristomanager.cloud.support.ApiClient.Actor;
import it.ristomanager.cloud.support.PostgresTestConfig;
import it.ristomanager.cloud.support.ScriptedLlmConfig;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Voice left at its default (off): the real placeholders are in place, the status says so and every call is refused with
 * 503. No speech fake is imported on purpose, and {@code app.voice.enabled} is not set, so this is the shipped default.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfig.class, ScriptedLlmConfig.class})
class VoiceDisabledIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    SpeechRecognizer recognizer;

    @Autowired
    SpeechSynthesizer synthesizer;

    ApiClient api;
    Actor owner;

    @BeforeEach
    void setUp() throws Exception {
        api = new ApiClient(mvc);
        owner = api.signup("R");
    }

    @Test
    void thePlaceholdersAreInPlace() {
        assertThat(recognizer).isInstanceOf(NoSpeechRecognizer.class);
        assertThat(synthesizer).isInstanceOf(NoSpeechSynthesizer.class);
    }

    @Test
    void theStatusSaysVoiceIsOff() throws Exception {
        api.get(owner, "/api/v1/voice/status").andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.speechToText").value(false))
                .andExpect(jsonPath("$.textToSpeech").value(false));
    }

    @Test
    void listeningIsRefusedWith503() throws Exception {
        mvc.perform(multipart("/api/v1/voice/transcribe")
                        .file(new MockMultipartFile("audio", "recording", "audio/webm",
                                "SOUND".getBytes(StandardCharsets.US_ASCII)))
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("Voice is not available"));
    }

    @Test
    void speakingIsRefusedWith503() throws Exception {
        api.post(owner, "/api/v1/voice/speak", "{\"text\":\"Ciao\",\"language\":\"it\"}")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("Voice is not available"));
    }
}
