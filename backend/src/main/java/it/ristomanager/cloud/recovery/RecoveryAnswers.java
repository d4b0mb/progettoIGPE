package it.ristomanager.cloud.recovery;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Makes answers forgiving without making them weak: "Rex!", "rex" and " REX " are the same answer, and
 * so are "Caffè" and "caffe". Only letters and digits count, accents are dropped, case is ignored.
 */
public final class RecoveryAnswers {

    public static final int MIN_LENGTH = 3;

    private RecoveryAnswers() {
    }

    public static String normalize(String answer) {
        if (answer == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(answer, Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder();
        for (char c : decomposed.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }
}
