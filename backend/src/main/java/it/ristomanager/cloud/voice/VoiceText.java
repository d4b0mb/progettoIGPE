package it.ristomanager.cloud.voice;

/** Cleaning of text that crosses the voice layer, in either direction. */
final class VoiceText {

    private VoiceText() {
    }

    /**
     * Control characters (newlines, tabs, escape and the like) become spaces, the result is cut to {@code maxChars}
     * (never in the middle of a surrogate pair) and trimmed. Null gives an empty string.
     */
    static String clean(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(Math.min(text.length(), maxChars));
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            out.append(Character.isISOControl(c) ? ' ' : c);
        }
        String cleaned = out.toString();
        if (cleaned.length() > maxChars) {
            int end = maxChars;
            if (end > 0 && Character.isHighSurrogate(cleaned.charAt(end - 1))) {
                end--;
            }
            cleaned = cleaned.substring(0, end);
        }
        return cleaned.strip();
    }

    /** Same replacement and trimming without any cut. */
    static String normalise(String text) {
        return clean(text, Integer.MAX_VALUE);
    }
}
