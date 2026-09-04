package com.nowserving.controller;

import com.nowserving.dto.AuthDtos.MeResponse.BusinessSummary;
import com.nowserving.dto.BusinessDtos.UpdateBusinessRequest;
import com.nowserving.entity.Business;
import com.nowserving.security.AuthenticatedOwner;
import com.nowserving.service.BusinessService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Owner settings — /business.
 *
 * Both routes fall under SecurityConfig's anyRequest().authenticated(), and
 * both take the business from the JWT principal rather than a path variable
 * or body field. There is deliberately NO /business/{id} form: an endpoint
 * that accepts an id is an endpoint that has to be taught who owns it, and
 * the safest version of that check is one that cannot be written wrong.
 */
@RestController
@RequiredArgsConstructor
public class BusinessController {

    private final BusinessService businessService;

    /** Rename my restaurant. Returns just the business — the client refreshes
     *  /me from it, rather than this endpoint re-deriving a whole MeResponse
     *  and inventing a displayName it was never asked to change. */
    @PatchMapping("/business")
    public BusinessSummary rename(@AuthenticationPrincipal AuthenticatedOwner principal,
                                  @Valid @RequestBody UpdateBusinessRequest request) {
        Business business = businessService.rename(principal.businessId(), request.name());
        return new BusinessSummary(business.getId(), business.getName(), business.getPublicToken());
    }

    /** Delete my restaurant and everything it owns. The JWT keeps working
     *  until it expires, but every row it could reach is gone, so /me 404s
     *  and the client treats that as logged out. */
    @DeleteMapping("/business")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthenticatedOwner principal) {
        businessService.deleteBusiness(principal.businessId());
    }
}
