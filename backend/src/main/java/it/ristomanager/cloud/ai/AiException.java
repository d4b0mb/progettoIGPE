package it.ristomanager.cloud.ai;

/** The language model could not give an answer. The kind tells callers whether to retry, wait or give up. */
public class AiException extends RuntimeException {

    public enum Kind {
        /** Turned off in configuration. */
        DISABLED,
        /** The model server could not be reached. */
        UNREACHABLE,
        /** The model server did not answer in time. */
        TIMEOUT,
        /** The model server answered with something we cannot use. */
        BAD_RESPONSE
    }

    private final Kind kind;

    public AiException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public AiException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
