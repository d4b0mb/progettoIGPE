package it.ristomanager.cloud.ai;

/**
 * Turns text into spoken audio. Intentionally unimplemented for now: no speech engine is chosen yet.
 *
 * It is separate from {@link LlmClient} because the two do different jobs (text in and text out, versus text
 * in and audio out) and will be swapped independently: a hosted service such as ElevenLabs or a free local
 * engine can be plugged in here without touching the language model.
 */
public interface SpeechSynthesizer {

    /** Returns the audio for the text, spoken in the given language (a BCP 47 tag such as "it-IT"). */
    byte[] speak(String text, String languageTag);

    boolean isAvailable();
}
