package demo.hb.web.exception;

import demo.hb.web.dto.ErrorResponse;
import demo.hb.web.exception.GenerationException.Code;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.HandlerMapping;

@RestControllerAdvice
public class GenerationErrorHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GenerationErrorHandler.class);

    @ExceptionHandler(GenerationException.class)
    public ResponseEntity<ErrorResponse> handle(GenerationException ex, HttpServletRequest request) {
        var status = status(ex.code());
        LOG.warn("HTTP 请求失败 method={} route={} httpStatus={} code={}", request.getMethod(),
                request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE), status.value(), ex.code());
        return ResponseEntity.status(status).body(new ErrorResponse(ex.code().name(), ex.getMessage()));
    }

    public static HttpStatus status(Code code) {
        return switch (code) {
            case INVALID_INPUT -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case REQUEST_TOO_LARGE -> HttpStatus.CONTENT_TOO_LARGE;
            case GENERATION_BUSY -> HttpStatus.TOO_MANY_REQUESTS;
            case GENERATION_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case AGENT_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case MODEL_NOT_CONFIGURED, INVALID_PAGE, MODEL_FAILURE -> HttpStatus.BAD_GATEWAY;
            case STORAGE_FAILURE -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
