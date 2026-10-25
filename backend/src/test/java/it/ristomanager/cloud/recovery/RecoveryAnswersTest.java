package it.ristomanager.cloud.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RecoveryAnswersTest {

    @Test
    void ignoresCaseSpacesAndPunctuation() {
        assertThat(RecoveryAnswers.normalize("  Rex! ")).isEqualTo("rex");
        assertThat(RecoveryAnswers.normalize("R.e.X")).isEqualTo("rex");
    }

    @Test
    void ignoresAccents() {
        assertThat(RecoveryAnswers.normalize("Caffè")).isEqualTo("caffe");
        assertThat(RecoveryAnswers.normalize("PERCHÈ")).isEqualTo("perche");
    }

    @Test
    void keepsDigitsAndRealDifferences() {
        assertThat(RecoveryAnswers.normalize("Via Roma 12")).isEqualTo("viaroma12");
        assertThat(RecoveryAnswers.normalize("rex")).isNotEqualTo(RecoveryAnswers.normalize("rexy"));
    }

    @Test
    void nothingGivesNothing() {
        assertThat(RecoveryAnswers.normalize(null)).isEmpty();
        assertThat(RecoveryAnswers.normalize("  !?  ")).isEmpty();
    }
}
