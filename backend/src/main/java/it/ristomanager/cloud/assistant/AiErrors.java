package it.ristomanager.cloud.assistant;

import it.ristomanager.cloud.ai.AiException;
import it.ristomanager.cloud.common.ApiException;
import org.springframework.http.HttpStatus;

/** Turns a failure of the language model into the answer the person sees. Shared by every feature that uses the model. */
public final class AiErrors {

    private AiErrors() {
    }

    public static ApiException toApi(AiException e) {
        return switch (e.kind()) {
            case DISABLED, UNREACHABLE ->
                    new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "The local AI model is not available");
            case TIMEOUT -> new ApiException(HttpStatus.GATEWAY_TIMEOUT, "The AI model took too long");
            case BAD_RESPONSE -> new ApiException(HttpStatus.BAD_GATEWAY, "The AI model gave an unusable answer");
        };
    }
}
