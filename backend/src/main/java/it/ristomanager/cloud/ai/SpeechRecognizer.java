package it.ristomanager.cloud.ai;

/**
 * Turns spoken audio into text: the "listening" half of a voice assistant. Intentionally unimplemented for now.
 *
 * Voice is two separate seams on purpose ({@link SpeechSynthesizer} speaks, this one listens), so each can be
 * swapped alone (for example a local Whisper model to listen and ElevenLabs or a local engine to speak). A
 * single model that takes audio and answers in audio would be a third implementation placed in front of
 * {@link LlmClient}, not a change to these two.
 */
public interface SpeechRecognizer {

    /** Returns what was said in the audio, in the given language (a BCP 47 tag such as "it-IT"). */
    String transcribe(byte[] audio, String audioMimeType, String languageTag);

    boolean isAvailable();
}
