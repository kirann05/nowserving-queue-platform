package com.nowserving.security;

/**
 * The identity we carry through a request once the JWT checks out — the
 * "principal" in Spring Security terms.
 *
 * businessId is here on purpose: NS-4's rule is "derive business_id from the
 * JWT, never from the request body". A client saying {"businessId": 42} in
 * JSON proves nothing; a signed token does. Every tenant-scoped query in the
 * services takes its businessId from THIS object.
 */
public record AuthenticatedOwner(Long ownerId, Long businessId, String email) {
}
