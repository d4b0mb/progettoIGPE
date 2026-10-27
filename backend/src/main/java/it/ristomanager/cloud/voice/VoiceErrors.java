package it.ristomanager.cloud.voice;

import it.ristomanager.cloud.ai.AiException;
import it.ristomanager.cloud.common.ApiException;
import org.springframework.http.HttpStatus;

/** Turns a failure of a speech engine into the answer the person sees. Same idea as the assistant's AiErrors, voice wording. */
final class VoiceErrors {

    private VoiceErrors() {
    }

    static ApiException toApi(AiException e) {
        return switch (e.kind()) {
            case DISABLED, UNREACHABLE -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Voice is not available");
            case TIMEOUT -> new ApiException(HttpStatus.GATEWAY_TIMEOUT, "Voice took too long");
            case BAD_RESPONSE -> new ApiException(HttpStatus.BAD_GATEWAY, "Voice gave an unusable answer");
        };
    }
}
