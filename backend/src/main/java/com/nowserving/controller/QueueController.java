package com.nowserving.controller;

import com.nowserving.dto.QueueDtos.AdvanceResponse;
import com.nowserving.dto.QueueDtos.CreateQueueRequest;
import com.nowserving.dto.QueueDtos.QueueResponse;
import com.nowserving.dto.QueueDtos.WaitingEntryResponse;
import com.nowserving.dto.QueueDtos.HistoryEntryResponse;
import com.nowserving.dto.QueueDtos.QueueStatsResponse;
import com.nowserving.dto.QueueDtos.QueueStatusRequest;
import com.nowserving.entity.EntryStatus;
import com.nowserving.dto.QueueDtos.VenueConfigRequest;
import com.nowserving.dto.QueueDtos.VenueConfigResponse;
import com.nowserving.security.AuthenticatedOwner;
import com.nowserving.service.QueueService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Owner/staff endpoints (NS-4, NS-7, NS-8). All of these sit behind the JWT
 * wall — see SecurityConfig: anything not /health, /auth/**, /public/** is
 * authenticated-only.
 *
 * Note the pattern in every method: principal.businessId() (from the signed
 * token) is passed into the service. Queue ids from the URL are never trusted
 * on their own.
 */
@RestController
@RequiredArgsConstructor
public class QueueController {

    private final QueueService queueService;

    // ---------- NS-4 ----------

    @PostMapping("/queues")
    @ResponseStatus(HttpStatus.CREATED)
    public QueueResponse create(@AuthenticationPrincipal AuthenticatedOwner principal,
                                @Valid @RequestBody CreateQueueRequest request) {
        return queueService.create(principal.businessId(), request);
    }

    @GetMapping("/queues")
    public List<QueueResponse> list(@AuthenticationPrincipal AuthenticatedOwner principal) {
        return queueService.list(principal.businessId());
    }

    @GetMapping("/queues/{id}")
    public QueueResponse get(@AuthenticationPrincipal AuthenticatedOwner principal,
                             @PathVariable Long id) {
        return queueService.get(principal.businessId(), id);
    }

    // ---------- NS-7 ----------

    /** The live line. History (?status=SERVED etc.) lives at /history below —
     *  the param used to 400 on anything but WAITING; now each shape has its
     *  own honest endpoint instead of one advertising filters it didn't do. */
    @GetMapping("/queues/{id}/entries")
    public List<WaitingEntryResponse> entries(@AuthenticationPrincipal AuthenticatedOwner principal,
                                              @PathVariable Long id) {
        return queueService.waitingEntries(principal.businessId(), id);
    }

    /** P1: completed entries (SERVED / NO_SHOW / LEFT) for a day. */
    @GetMapping("/queues/{id}/history")
    public List<HistoryEntryResponse> history(
            @AuthenticationPrincipal AuthenticatedOwner principal,
            @PathVariable Long id,
            @RequestParam(defaultValue = "SERVED") EntryStatus status,
            @RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(
                    iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
            java.time.LocalDate date) {
        return queueService.history(principal.businessId(), id, status, date);
    }

    /** P1: the operational summary the dashboard shows. */
    @GetMapping("/queues/{id}/stats")
    public QueueStatsResponse stats(@AuthenticationPrincipal AuthenticatedOwner principal,
                                    @PathVariable Long id) {
        return queueService.stats(principal.businessId(), id);
    }

    /**
     * P0: open/close, explicitly and audited — never via the database.
     * PATCH because it changes one field of the resource, not the whole thing.
     */
    @PatchMapping("/queues/{id}/status")
    public QueueResponse changeStatus(@AuthenticationPrincipal AuthenticatedOwner principal,
                                      @PathVariable Long id,
                                      @Valid @RequestBody QueueStatusRequest request) {
        return queueService.changeStatus(principal.businessId(), id, principal.email(), request);
    }

    // ---------- NS-8 ----------

    // ---------- Sprint 5: venue location + late-arrival policy ----------

    @GetMapping("/queues/{id}/venue-config")
    public VenueConfigResponse venueConfig(@AuthenticationPrincipal AuthenticatedOwner principal,
                                           @PathVariable Long id) {
        return queueService.venueConfig(principal.businessId(), id);
    }

    @PutMapping("/queues/{id}/venue-config")
    public VenueConfigResponse configureVenue(@AuthenticationPrincipal AuthenticatedOwner principal,
                                              @PathVariable Long id,
                                              @Valid @RequestBody VenueConfigRequest request) {
        return queueService.configureVenue(principal.businessId(), id, request);
    }

    @PostMapping("/queues/{id}/advance")
    public AdvanceResponse advance(@AuthenticationPrincipal AuthenticatedOwner principal,
                                   @PathVariable Long id) {
        return queueService.advance(principal.businessId(), id);
    }

    /**
     * DELETE marks the entry NO_SHOW rather than deleting the row — a "soft
     * delete". The record still exists for history/analytics (Sprint 5 wants
     * real service stats); it just leaves the line. 204 No Content is the
     * conventional response for a successful DELETE with nothing to say.
     */
    @DeleteMapping("/entries/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeNoShow(@AuthenticationPrincipal AuthenticatedOwner principal,
                             @PathVariable Long id) {
        queueService.markNoShow(principal.businessId(), id);
    }
}
