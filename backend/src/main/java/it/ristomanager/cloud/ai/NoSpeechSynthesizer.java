package it.ristomanager.cloud.ai;

/** Placeholder used until a real speech engine is configured. */
class NoSpeechSynthesizer implements SpeechSynthesizer {

    @Override
    public byte[] speak(String text, String languageTag) {
        throw new UnsupportedOperationException("No speech engine configured");
    }

    @Override
    public boolean isAvailable() {
        return false;
    }
}
