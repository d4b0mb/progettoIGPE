package it.ristomanager.cloud.voice;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** What the voice endpoints exchange. */
public final class VoiceTypes {

    private VoiceTypes() {
    }

    /**
     * Whether voice is switched on and each engine is answering. {@code speechToText} and {@code textToSpeech} are false
     * when voice is off.
     */
    public record VoiceStatus(boolean enabled, boolean speechToText, boolean textToSpeech) {
    }

    /** What was heard: plain text for the person to read or send to the assistant. Empty when nothing was said. */
    public record TranscribeResult(String text) {
    }

    /**
     * Text to speak. {@code language} is "it" or "en" (or absent); {@code voice} names one of the engine's voices (or is
     * absent for the default of the language). The server limits the length further (app.voice.limits.max-text-chars).
     */
    public record SpeakRequest(@NotBlank @Size(max = 2000) String text, String language, String voice) {
    }
}
