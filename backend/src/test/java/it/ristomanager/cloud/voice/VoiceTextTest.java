package it.ristomanager.cloud.voice;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VoiceTextTest {

    @Test
    void controlCharactersBecomeSpacesAndTheResultIsTrimmed() {
        assertThat(VoiceText.clean("\n  Un tavolo\nper due\u0007\t ", 1000)).isEqualTo("Un tavolo per due");
    }

    @Test
    void nullIsEmpty() {
        assertThat(VoiceText.clean(null, 10)).isEmpty();
        assertThat(VoiceText.normalise(null)).isEmpty();
    }

    @Test
    void theTextIsCutToTheLimit() {
        assertThat(VoiceText.clean("abcdef", 3)).isEqualTo("abc");
        assertThat(VoiceText.clean("abc", 3)).isEqualTo("abc");
    }

    @Test
    void aCutNeverSplitsASurrogatePair() {
        String pizza = "ab🍕cd";

        assertThat(VoiceText.clean(pizza, 3)).isEqualTo("ab");
        assertThat(VoiceText.clean(pizza, 4)).isEqualTo("ab🍕");
    }

    @Test
    void normaliseDoesNotCut() {
        assertThat(VoiceText.normalise("a".repeat(5000))).hasSize(5000);
    }
}
