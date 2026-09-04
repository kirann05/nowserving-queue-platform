package com.nowserving.exception;

import org.springframework.http.HttpStatus;

/** 409 — the request is valid but collides with existing state (duplicate email). */
public class ConflictException extends ApiException {
    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
