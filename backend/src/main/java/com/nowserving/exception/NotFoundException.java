package com.nowserving.exception;

import org.springframework.http.HttpStatus;

/**
 * 404. Also deliberately used for "exists, but belongs to another tenant"
 * (NS-4): returning 403 would leak that the resource exists at all.
 */
public class NotFoundException extends ApiException {
    public NotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, message);
    }
}
