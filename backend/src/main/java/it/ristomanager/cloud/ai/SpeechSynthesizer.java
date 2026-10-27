package it.ristomanager.cloud.ai;

/**
 * Turns text into spoken audio: the "speaking" half of voice. A thin layer around text, it never decides anything.
 *
 * It is separate from {@link LlmClient} because the two do different jobs (text in and text out, versus text
 * in and audio out) and are swapped independently: a hosted service such as ElevenLabs or a free local
 * engine can be plugged in here without touching the language model.
 */
public interface SpeechSynthesizer {

    /**
     * Returns the audio for the text, spoken in the given language.
     *
     * @param languageTag a language tag such as "it" or "en"
     * @param voice       the engine's voice name, or null for the configured default of that language
     * @throws AiException (DISABLED, UNREACHABLE, TIMEOUT or BAD_RESPONSE) when no audio could be made
     */
    SpeechAudio speak(String text, String languageTag, String voice);

    boolean isAvailable();
}
