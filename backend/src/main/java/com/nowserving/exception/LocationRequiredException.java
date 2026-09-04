package com.nowserving.exception;

import org.springframework.http.HttpStatus;

/**
 * V9: "I can't answer this until you tell me roughly where you are."
 *
 * Thrown when a customer tries to join REMOTELY (not by scanning the QR at
 * the door) and the restaurant enforces a distance limit. We cannot approve
 * or refuse them without a location, so the request is incomplete rather than
 * wrong.
 *
 * WHY A DISTINCT STATUS AND NOT JUST 400: the frontend has to react
 * differently. A 400 means "you did something invalid — show the message";
 * this means "ask the browser for permission and retry". Encoding that in
 * the status code keeps the client from string-matching on error text, which
 * breaks the moment someone improves the copy.
 *
 * 428 Precondition Required (RFC 6585) is the honest fit: the request is
 * valid, but the server requires it to be conditional on something the
 * client hasn't supplied yet.
 */
public class LocationRequiredException extends ApiException {

    public LocationRequiredException(String message) {
        super(HttpStatus.PRECONDITION_REQUIRED, message);
    }
}
