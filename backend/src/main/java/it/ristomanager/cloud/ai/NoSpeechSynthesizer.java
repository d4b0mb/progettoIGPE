package it.ristomanager.cloud.ai;

import it.ristomanager.cloud.ai.AiException.Kind;

/** Used when voice is switched off: every call is refused with {@link Kind#DISABLED}. */
public class NoSpeechSynthesizer implements SpeechSynthesizer {

    @Override
    public SpeechAudio speak(String text, String languageTag, String voice) {
        throw new AiException(Kind.DISABLED, "Speech synthesis is disabled");
    }

    @Override
    public boolean isAvailable() {
        return false;
    }
}
