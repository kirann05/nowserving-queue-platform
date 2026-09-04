package com.nowserving.controller;

import com.nowserving.dto.PublicDtos.JoinResponse;
import com.nowserving.dto.ReservationDtos.BookingConfigRequest;
import com.nowserving.dto.ReservationDtos.BookingConfigResponse;
import com.nowserving.dto.ReservationDtos.CreateReservationRequest;
import com.nowserving.dto.ReservationDtos.DayAvailability;
import com.nowserving.dto.ReservationDtos.ReservationResponse;
import com.nowserving.dto.ReservationDtos.ReservationSummary;
import com.nowserving.security.AuthenticatedOwner;
import com.nowserving.service.QueueService;
import com.nowserving.service.ReservationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * FR-10 endpoints. Two audiences, and the split mirrors the rest of the app:
 *  - /queues/{id}/booking-config  — owner, JWT-protected, tenant-scoped
 *  - /public/**                   — customer, no account, token-authorised
 */
@RestController
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;
    private final QueueService queueService;

    // ---------- owner ----------

    @GetMapping("/queues/{id}/booking-config")
    public BookingConfigResponse bookingConfig(@AuthenticationPrincipal AuthenticatedOwner principal,
                                               @PathVariable Long id) {
        return queueService.bookingConfig(principal.businessId(), id);
    }

    @PutMapping("/queues/{id}/booking-config")
    public BookingConfigResponse configureBooking(@AuthenticationPrincipal AuthenticatedOwner principal,
                                                  @PathVariable Long id,
                                                  @Valid @RequestBody BookingConfigRequest request) {
        return queueService.configureBooking(principal.businessId(), id, request);
    }

    @GetMapping("/queues/{id}/reservations")
    public List<ReservationSummary> reservationsForDay(
            @AuthenticationPrincipal AuthenticatedOwner principal,
            @PathVariable Long id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return queueService.reservationsForDay(principal.businessId(), id, date);
    }

    // ---------- customer ----------

    /** What's free on this date? Public, because you book before you have any account. */
    @GetMapping("/public/queues/{joinToken}/availability")
    public DayAvailability availability(
            @PathVariable String joinToken,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return reservationService.availability(joinToken, date);
    }

    /**
     * Book a slot.
     *
     * The Idempotency-Key header is how a double-tap or a retry on a flaky
     * connection stays ONE booking. It's a header rather than a body field
     * because it describes the REQUEST, not the reservation — the same
     * convention every payments API uses.
     */
    @PostMapping("/public/queues/{joinToken}/reservations")
    @ResponseStatus(HttpStatus.CREATED)
    public ReservationResponse book(@PathVariable String joinToken,
                                    @RequestHeader(value = "Idempotency-Key", required = false)
                                    String idempotencyKey,
                                    @Valid @RequestBody CreateReservationRequest request) {
        return reservationService.book(joinToken, request, idempotencyKey);
    }

    @GetMapping("/public/reservations/{reservationToken}")
    public ReservationResponse lookup(@PathVariable String reservationToken) {
        return reservationService.lookup(reservationToken);
    }

    /** "I'm here" — turns the booking into a live place in the line. */
    @PostMapping("/public/reservations/{reservationToken}/check-in")
    public JoinResponse checkIn(@PathVariable String reservationToken) {
        return reservationService.redeem(reservationToken);
    }

    @DeleteMapping("/public/reservations/{reservationToken}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable String reservationToken) {
        reservationService.cancel(reservationToken);
    }
}
