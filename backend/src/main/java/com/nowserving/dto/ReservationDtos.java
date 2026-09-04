package com.nowserving.dto;

import com.nowserving.entity.ReservationStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

/** Shapes for FR-10 — reservations. */
public final class ReservationDtos {

    private ReservationDtos() {}

    /** Owner sets when they're open and how bookings are chopped up. */
    public record BookingConfigRequest(
            @NotNull Boolean reservationsEnabled,
            /** Wall-clock local times, e.g. 09:00 / 17:00. */
            LocalTime openingTime,
            LocalTime closingTime,
            @Min(5) @Max(480) Integer slotMinutes,
            @Min(1) @Max(100) Integer slotCapacity,
            /** IANA zone id, e.g. "America/Chicago" — never a raw offset. */
            String timeZone) {}

    public record BookingConfigResponse(
            boolean reservationsEnabled,
            LocalTime openingTime,
            LocalTime closingTime,
            int slotMinutes,
            int slotCapacity,
            String timeZone) {}

    /** One bookable slot on a given day. */
    public record SlotResponse(
            Instant startTime,
            int durationMinutes,
            int capacity,
            int booked,
            boolean available) {}

    public record CreateReservationRequest(
            @NotBlank @Size(max = 100) String customerName,
            @Size(max = 255) String contact,
            @Min(1) Integer partySize,
            /** Which slot, as an exact instant (avoids all zone ambiguity). */
            @NotNull Instant slotStart) {}

    public record ReservationResponse(
            String reservationToken,
            /** WHICH RESTAURANT. The confirmation screen showed only the
             *  queue name ("Dinner Service"), which tells a customer who
             *  booked from a list of restaurants nothing about where to
             *  turn up. */
            String businessName,
            String queueName,
            Instant slotStart,
            int durationMinutes,
            String customerName,
            int partySize,
            ReservationStatus status) {}

    /** Staff view of the day's bookings. */
    public record ReservationSummary(
            Long id,
            Instant slotStart,
            String customerName,
            int partySize,
            ReservationStatus status) {}

    public record DayAvailability(Instant dayStart, List<SlotResponse> slots) {}
}
