package com.nowserving.exception;

import org.springframework.http.HttpStatus;

/** 400 — a well-formed request that the domain rejects (queue closed, no one waiting). */
public class BadRequestException extends ApiException {
    public BadRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
