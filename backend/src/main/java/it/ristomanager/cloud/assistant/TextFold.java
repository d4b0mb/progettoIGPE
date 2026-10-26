package it.ristomanager.cloud.assistant;

import java.text.Normalizer;
import java.util.Locale;

/** Text comparison that ignores case and accents, so "Perù" is found by "peru" and "Rossì" by "ROSSI". */
final class TextFold {

    private TextFold() {
    }

    static String fold(String text) {
        if (text == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).trim();
    }

    /** True when {@code text} contains {@code part}, ignoring case and accents. An empty part matches everything. */
    static boolean containsFolded(String text, String part) {
        return fold(text).contains(fold(part));
    }

    /** At most {@code max} characters; null becomes empty. */
    static String cut(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
