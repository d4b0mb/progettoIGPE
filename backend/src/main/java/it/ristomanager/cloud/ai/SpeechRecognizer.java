package it.ristomanager.cloud.ai;

/**
 * Turns spoken audio into text: the "listening" half of voice. A thin layer around text: what comes back is only
 * text for the person (or the assistant) to read, it is never a command.
 *
 * Voice is two separate seams on purpose ({@link SpeechSynthesizer} speaks, this one listens), so each can be
 * swapped alone (for example a local Whisper model to listen and ElevenLabs or a local engine to speak). A
 * single model that takes audio and answers in audio would be a third implementation placed in front of
 * {@link LlmClient}, not a change to these two.
 */
public interface SpeechRecognizer {

    /**
     * Returns what was said in the audio; an empty string means nothing was recognised (silence).
     *
     * @param audioMimeType the MIME type of the audio, for example "audio/webm"
     * @param languageTag   a language tag such as "it" or "en", or null to let the engine detect the language
     * @throws AiException (DISABLED, UNREACHABLE, TIMEOUT or BAD_RESPONSE) when the audio could not be transcribed
     */
    String transcribe(byte[] audio, String audioMimeType, String languageTag);

    boolean isAvailable();
}
