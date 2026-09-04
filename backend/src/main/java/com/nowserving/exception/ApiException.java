package com.nowserving.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Base class for "expected" errors — situations the API contract documents
 * (duplicate email -> 409, unknown queue -> 404, ...). Services throw these;
 * the GlobalExceptionHandler turns them into consistent JSON responses.
 *
 * Anything that does NOT extend this is a genuine bug and becomes a 500.
 */
@Getter
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;

    protected ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }
}
