package demo.hb.web.exception;

public final class GenerationException extends RuntimeException {

    private final Code code;

    public GenerationException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        INVALID_INPUT,
        INVALID_PAGE,
        NOT_FOUND,
        CONFLICT,
        REQUEST_TOO_LARGE,
        GENERATION_BUSY,
        GENERATION_TIMEOUT,
        AGENT_UNAVAILABLE,
        MODEL_NOT_CONFIGURED,
        MODEL_FAILURE,
        STORAGE_FAILURE
    }
}
