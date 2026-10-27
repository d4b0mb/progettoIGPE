package it.ristomanager.cloud.ai;

/** Spoken audio as a speech engine returned it: the encoded bytes and their MIME type (for example "audio/mpeg"). */
public record SpeechAudio(byte[] bytes, String mimeType) {
}
