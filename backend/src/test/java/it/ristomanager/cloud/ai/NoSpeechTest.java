package it.ristomanager.cloud.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.ristomanager.cloud.ai.AiException.Kind;
import org.junit.jupiter.api.Test;

/** The placeholders used while voice is switched off refuse every call the same way a real provider failure would. */
class NoSpeechTest {

    @Test
    void theRecognizerIsUnavailableAndRefusesWithDisabled() {
        NoSpeechRecognizer recognizer = new NoSpeechRecognizer();

        assertThat(recognizer.isAvailable()).isFalse();
        assertThatThrownBy(() -> recognizer.transcribe(new byte[] {1}, "audio/webm", "it"))
                .isInstanceOfSatisfying(AiException.class, e -> assertThat(e.kind()).isEqualTo(Kind.DISABLED));
    }

    @Test
    void theSynthesizerIsUnavailableAndRefusesWithDisabled() {
        NoSpeechSynthesizer synthesizer = new NoSpeechSynthesizer();

        assertThat(synthesizer.isAvailable()).isFalse();
        assertThatThrownBy(() -> synthesizer.speak("Ciao", "it", null))
                .isInstanceOfSatisfying(AiException.class, e -> assertThat(e.kind()).isEqualTo(Kind.DISABLED));
    }
}
