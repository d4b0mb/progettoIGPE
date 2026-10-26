package it.ristomanager.cloud.ai;

/** Placeholder used until a real speech-to-text engine is configured. */
class NoSpeechRecognizer implements SpeechRecognizer {

    @Override
    public String transcribe(byte[] audio, String audioMimeType, String languageTag) {
        throw new UnsupportedOperationException("No speech recognition engine configured");
    }

    @Override
    public boolean isAvailable() {
        return false;
    }
}
