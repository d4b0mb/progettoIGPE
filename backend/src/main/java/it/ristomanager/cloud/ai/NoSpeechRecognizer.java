package it.ristomanager.cloud.ai;

import it.ristomanager.cloud.ai.AiException.Kind;

/** Used when voice is switched off: every call is refused with {@link Kind#DISABLED}. */
public class NoSpeechRecognizer implements SpeechRecognizer {

    @Override
    public String transcribe(byte[] audio, String audioMimeType, String languageTag) {
        throw new AiException(Kind.DISABLED, "Speech recognition is disabled");
    }

    @Override
    public boolean isAvailable() {
        return false;
    }
}
