package com.nowserving.exception;

import org.springframework.http.HttpStatus;

/** 401 — missing/invalid credentials or token. */
public class UnauthorizedException extends ApiException {
    public UnauthorizedException(String message) {
        super(HttpStatus.UNAUTHORIZED, message);
    }
}
