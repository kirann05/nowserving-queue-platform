package com.nowserving.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * NS-2: one place that turns exceptions into HTTP responses, so every error
 * the API ever returns has the SAME JSON shape. Without this, Spring's
 * defaults leak stack traces and each controller invents its own error format.
 *
 * @RestControllerAdvice = "these @ExceptionHandler methods apply to every
 * controller in the app."
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** The single error shape clients can rely on. */
    public record ApiError(Instant timestamp, int status, String error, String message,
                           Map<String, String> fieldErrors) {

        static ApiError of(HttpStatus status, String message) {
            return new ApiError(Instant.now(), status.value(), status.getReasonPhrase(), message, null);
        }
    }

    /** Our own domain exceptions carry their status with them. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.getStatus()).body(ApiError.of(ex.getStatus(), ex.getMessage()));
    }

    /**
     * Bean Validation failures (@Valid on a DTO) -> 400 with a per-field map,
     * so a future UI can highlight the exact offending inputs.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(fe -> fields.put(fe.getField(), fe.getDefaultMessage()));
        var status = HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status)
                .body(new ApiError(Instant.now(), status.value(), status.getReasonPhrase(),
                        "Validation failed", fields));
    }

    /**
     * Last-resort catch-all. The client gets a generic 500 (internal details
     * are none of their business and could aid an attacker); the full stack
     * trace goes to OUR logs, which is where debugging happens.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        var status = HttpStatus.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(status).body(ApiError.of(status, "Something went wrong"));
    }
}
