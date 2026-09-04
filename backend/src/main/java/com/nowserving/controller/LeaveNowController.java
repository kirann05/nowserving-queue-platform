package com.nowserving.controller;

import com.nowserving.dto.PublicDtos.LeaveNowResponse;
import com.nowserving.dto.PublicDtos.ShareLocationRequest;
import com.nowserving.entity.QueueEntry;
import com.nowserving.exception.NotFoundException;
import com.nowserving.repository.QueueEntryRepository;
import com.nowserving.service.CustomerLocationService;
import com.nowserving.service.GracePeriodService;
import com.nowserving.service.LeaveNowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * Sprint 5's customer-facing surface (FR-11 … FR-16).
 *
 * All of it is authorised by the ENTRY TOKEN — the same capability model as
 * checking your position. Location is some of the most sensitive data this
 * product will ever touch, so it is worth being explicit: possession of the
 * private ticket link is what lets you set or clear the location for that
 * ticket, and there is no endpoint anywhere that reads a customer's
 * coordinates back out. Not even staff can see them; they see an ETA.
 */
@RestController
@RequiredArgsConstructor
public class LeaveNowController {

    private final CustomerLocationService locationService;
    private final LeaveNowService leaveNowService;
    private final GracePeriodService gracePeriodService;
    private final QueueEntryRepository entryRepository;
    private final com.nowserving.travel.TomTomTravelTimeProvider tomTom;

    /** FR-11: opt in by sharing a position (stored coarse, in Redis, with a TTL). */
    @PostMapping("/public/entries/{entryToken}/location")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void shareLocation(@PathVariable String entryToken,
                              @Valid @RequestBody ShareLocationRequest request) {
        locationService.share(entryToken, request.latitude(), request.longitude());
    }

    /** FR-11: "revocable" — and the stored position is deleted immediately. */
    @DeleteMapping("/public/entries/{entryToken}/location")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeLocation(@PathVariable String entryToken) {
        locationService.revoke(entryToken);
    }

    /** FR-12/FR-13: "when should I leave?" — polled by the ticket page. */
    @GetMapping("/public/entries/{entryToken}/leave-now")
    @Transactional(readOnly = true)
    public LeaveNowResponse leaveNow(@PathVariable String entryToken) {
        QueueEntry entry = entryRepository.findByEntryToken(entryToken)
                .orElseThrow(() -> new NotFoundException("Ticket not found"));

        var queue = entry.getQueue();
        Double vLat = queue.hasVenueLocation() ? queue.getVenueLatitude() : null;
        Double vLon = queue.hasVenueLocation() ? queue.getVenueLongitude() : null;
        return leaveNowService.statusFor(entry)
                .map(s -> new LeaveNowResponse(s.sharingLocation(), s.travelMinutes(),
                        s.trafficAware(), s.trafficDelayMinutes(), s.leaveBy(),
                        s.shouldLeaveNow(), s.enRoute(), entry.getGraceExpiresAt(),
                        s.expectedTurnAt(), s.turnWindowMinutes(), vLat, vLon))
                // Served / no-show tickets have no journey to talk about.
                .orElse(new LeaveNowResponse(false, null, false, null, null, false, false,
                        null, null, 0, null, null));
    }

    /** FR-15: "on my way" — appears on the staff dashboard. */
    @PostMapping("/public/entries/{entryToken}/on-my-way")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void onMyWay(@PathVariable String entryToken) {
        gracePeriodService.markEnRoute(entryToken);
    }

    /** FR-16: "hold my spot, I'm nearly there". */
    @PostMapping("/public/entries/{entryToken}/hold")
    public HoldResponse hold(@PathVariable String entryToken) {
        return new HoldResponse(gracePeriodService.requestHold(entryToken));
    }

    public record HoldResponse(java.time.Instant graceExpiresAt) {}

    /**
     * Owner-side address search (TomTom Search v2) so venue setup never means
     * typing raw coordinates. AUTHENTICATED — not under /public — because
     * every call spends free-tier quota (~2.5k/month on legacy Search) and an
     * open geocoder would be farmed by scripts within days.
     * Returns [] when TomTom isn't configured; the UI falls back to manual
     * lat/lon and the one-tap "use this device's location".
     */
    @GetMapping("/geo/search")
    public java.util.List<com.nowserving.travel.TomTomTravelTimeProvider.GeocodeResult> searchAddress(
            @RequestParam String q) {
        return tomTom.searchAddress(q);
    }
}
