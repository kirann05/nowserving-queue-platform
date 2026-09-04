package com.nowserving.service;

import com.nowserving.dto.ReservationDtos.CreateReservationRequest;
import com.nowserving.dto.ReservationDtos.DayAvailability;
import com.nowserving.dto.ReservationDtos.ReservationResponse;
import com.nowserving.dto.ReservationDtos.SlotResponse;
import com.nowserving.dto.PublicDtos.JoinResponse;
import com.nowserving.entity.Queue;
import com.nowserving.entity.QueueEntry;
import com.nowserving.entity.Reservation;
import com.nowserving.entity.ReservationStatus;
import com.nowserving.exception.BadRequestException;
import com.nowserving.exception.ConflictException;
import com.nowserving.exception.NotFoundException;
import com.nowserving.repository.QueueEntryRepository;
import com.nowserving.repository.QueueRepository;
import com.nowserving.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * FR-10 — booking a future slot.
 *
 * Slot times are DERIVED from the queue's opening hours rather than stored as
 * rows (see V4__reservations.sql for why), and capacity is enforced by a
 * database UNIQUE constraint on (queue, slot, seat) rather than by counting.
 */
@Service
@RequiredArgsConstructor
public class ReservationService {

    /** How many times to retry when another customer grabs the seat first. */
    private static final int SEAT_RACE_RETRIES = 5;

    private final QueueRepository queueRepository;
    private final ReservationRepository reservationRepository;
    /** Separate bean on purpose — see its class comment (proxy + retry). */
    private final ReservationSeatWriter seatWriter;
    private final QueueEntryRepository entryRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    // ---------- availability ----------

    /**
     * Every slot on one local day, with how many seats are gone.
     *
     * Time zones done properly: opening_time is a WALL-CLOCK fact ("we open at
     * 9"). Turning that into a real instant needs the business's zone, and
     * doing it via ZonedDateTime means daylight-saving days with 23 or 25
     * hours come out right. Adding fixed millisecond offsets — the obvious
     * implementation — silently breaks twice a year.
     */
    @Transactional(readOnly = true)
    public DayAvailability availability(String joinToken, LocalDate date) {
        Queue queue = queueRepository.findByJoinToken(joinToken)
                .orElseThrow(() -> new NotFoundException("Queue not found"));
        requireBookingEnabled(queue);

        ZoneId zone = ZoneId.of(queue.getTimeZone());
        ZonedDateTime dayStart = date.atStartOfDay(zone);

        List<Instant> slotStarts = slotStartsFor(queue, date, zone);
        Instant from = slotStarts.isEmpty() ? dayStart.toInstant() : slotStarts.get(0);
        Instant to = slotStarts.isEmpty()
                ? dayStart.toInstant()
                : slotStarts.get(slotStarts.size() - 1).plusSeconds(1);

        // One query for the whole day, then group in memory — rather than a
        // query per slot (a classic N+1).
        List<Reservation> booked = reservationRepository
                .findByQueueIdAndSlotStartBetweenAndStatusNot(
                        queue.getId(), from, to, ReservationStatus.CANCELLED);

        Instant now = clock.instant();
        List<SlotResponse> slots = new ArrayList<>();
        for (Instant start : slotStarts) {
            long taken = booked.stream().filter(r -> r.getSlotStart().equals(start)).count();
            boolean inFuture = start.isAfter(now);
            slots.add(new SlotResponse(
                    start,
                    queue.getSlotMinutes(),
                    queue.getSlotCapacity(),
                    (int) taken,
                    inFuture && taken < queue.getSlotCapacity()));
        }
        return new DayAvailability(dayStart.toInstant(), slots);
    }

    // ---------- booking ----------

    /**
     * Book a seat. Two safety properties, both worth understanding:
     *
     * 1. IDEMPOTENCY — if the caller supplies a key we've seen before, we
     *    return the ORIGINAL booking. A customer whose response was eaten by a
     *    flaky connection can tap again without ending up with two seats.
     *
     * 2. NO DOUBLE-BOOKING — we pick the lowest free seat and insert. If
     *    another customer took it a millisecond earlier, the database's UNIQUE
     *    constraint rejects our insert and we retry with the next seat. We
     *    never COUNT-then-INSERT, because the count is stale the instant it is
     *    read — that gap is exactly the NS-8 race in a new costume.
     */
    public ReservationResponse book(String joinToken, CreateReservationRequest request,
                                    String idempotencyKey) {
        Queue queue = queueRepository.findByJoinToken(joinToken)
                .orElseThrow(() -> new NotFoundException("Queue not found"));
        requireBookingEnabled(queue);
        validateSlot(queue, request.slotStart());

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<Reservation> existing =
                    reservationRepository.findByQueueIdAndIdempotencyKey(queue.getId(), idempotencyKey);
            if (existing.isPresent()) {
                return toResponse(existing.get(), queue);
            }
        }

        // Resolve the LAZY business association here, while the persistence
        // context is still clean — see the toResponse overload below for why
        // doing it after a caught DataIntegrityViolationException breaks.
        String businessName = queue.getBusiness().getName();

        for (int attempt = 0; attempt < SEAT_RACE_RETRIES; attempt++) {
            int seat = nextFreeSeat(queue, request.slotStart());
            try {
                Reservation saved = seatWriter.insert(
                        queue, request.slotStart(), seat,
                        request.customerName().trim(), request.contact(),
                        request.partySize() != null ? request.partySize() : 1,
                        idempotencyKey);
                return toResponse(saved, queue, businessName);
            } catch (DataIntegrityViolationException e) {
                // Someone else won this seat (or this exact idempotency key
                // landed concurrently). Look again and try the next one.
                Optional<Reservation> raced = idempotencyKey == null ? Optional.empty()
                        : reservationRepository.findByQueueIdAndIdempotencyKey(queue.getId(), idempotencyKey);
                if (raced.isPresent()) {
                    return toResponse(raced.get(), queue, businessName);
                }
            }
        }
        throw new ConflictException("That time slot just filled up — please pick another");
    }

    @Transactional(readOnly = true)
    public ReservationResponse lookup(String reservationToken) {
        Reservation reservation = reservationRepository.findByReservationToken(reservationToken)
                .orElseThrow(() -> new NotFoundException("Reservation not found"));
        return toResponse(reservation, reservation.getQueue());
    }

    /**
     * The customer with a booking arrives and is placed into the live line —
     * the moment the two front doors become one thing.
     *
     * The redeemed entry keeps its BOOKED time as targetServeTime, so a
     * customer who booked 15:00 is due at 15:00 even if walk-ins are backed
     * up. Everything downstream reads that one field.
     */
    @Transactional
    public JoinResponse redeem(String reservationToken) {
        Reservation reservation = reservationRepository.findByReservationToken(reservationToken)
                .orElseThrow(() -> new NotFoundException("Reservation not found"));

        if (reservation.getStatus() == ReservationStatus.CANCELLED) {
            throw new BadRequestException("This reservation was cancelled");
        }
        if (reservation.getStatus() == ReservationStatus.REDEEMED) {
            throw new BadRequestException("This reservation has already been checked in");
        }

        Queue queue = reservation.getQueue();
        QueueEntry entry = new QueueEntry(
                queue, reservation.getCustomerName(), reservation.getPartySize());
        entry.setReservation(reservation);
        entry.setTargetServeTime(reservation.getSlotStart()); // the booked time IS the target
        entry = entryRepository.save(entry);

        reservation.setStatus(ReservationStatus.REDEEMED);

        // Same event as a walk-in join: staff dashboards and the realtime
        // layer neither know nor care that this one arrived via a booking.
        eventPublisher.publishEvent(QueueChangedEvent.of(queue.getId()));

        long ahead = entryRepository.countWaitingAhead(
                queue.getId(), entry.getQueueOrderAt(), entry.getId());
        int position = (int) ahead + 1;
        return new JoinResponse(entry.getEntryToken(), queue.getName(),
                position, position - 1, 0);
    }

    @Transactional
    public void cancel(String reservationToken) {
        Reservation reservation = reservationRepository.findByReservationToken(reservationToken)
                .orElseThrow(() -> new NotFoundException("Reservation not found"));
        if (reservation.getStatus() == ReservationStatus.REDEEMED) {
            throw new BadRequestException("This reservation has already been used");
        }
        // Cancelling frees the seat for someone else: availability ignores
        // CANCELLED rows, and the UNIQUE constraint still holds the seat
        // number, so the next booker simply takes a different seat index.
        reservation.setStatus(ReservationStatus.CANCELLED);
    }

    // ---------- helpers ----------

    private int nextFreeSeat(Queue queue, Instant slotStart) {
        Set<Integer> taken = new HashSet<>();
        reservationRepository
                .findByQueueIdAndSlotStartAndStatusNot(queue.getId(), slotStart, ReservationStatus.CANCELLED)
                .forEach(r -> taken.add(r.getSeatNo()));

        for (int seat = 0; seat < queue.getSlotCapacity(); seat++) {
            if (!taken.contains(seat)) return seat;
        }
        throw new ConflictException("That time slot is fully booked");
    }

    private List<Instant> slotStartsFor(Queue queue, LocalDate date, ZoneId zone) {
        List<Instant> starts = new ArrayList<>();
        if (queue.getOpeningTime() == null || queue.getClosingTime() == null) {
            return starts;
        }
        ZonedDateTime cursor = date.atTime(queue.getOpeningTime()).atZone(zone);
        ZonedDateTime end = date.atTime(queue.getClosingTime()).atZone(zone);
        Duration step = Duration.ofMinutes(queue.getSlotMinutes());

        // plus(Duration) on a ZonedDateTime respects DST transitions — this is
        // the line that would be wrong if we added raw milliseconds.
        while (cursor.plus(step).compareTo(end) <= 0) {
            starts.add(cursor.toInstant());
            cursor = cursor.plus(step);
        }
        return starts;
    }

    private void validateSlot(Queue queue, Instant slotStart) {
        if (!slotStart.isAfter(clock.instant())) {
            throw new BadRequestException("That time has already passed");
        }
        ZoneId zone = ZoneId.of(queue.getTimeZone());
        LocalDate date = slotStart.atZone(zone).toLocalDate();
        if (!slotStartsFor(queue, date, zone).contains(slotStart)) {
            // Stops a crafted request booking 03:17 on a closed Sunday.
            throw new BadRequestException("That is not a bookable time slot");
        }
    }

    private void requireBookingEnabled(Queue queue) {
        if (!queue.isReservationsEnabled()) {
            throw new BadRequestException("This queue does not take reservations");
        }
    }

    private ReservationResponse toResponse(Reservation reservation, Queue queue) {
        return toResponse(reservation, queue, queue.getBusiness().getName());
    }

    /**
     * Overload taking an already-resolved business name.
     *
     * Queue.business is LAZY, so reading it emits a SELECT. That is harmless
     * almost everywhere — but NOT inside the seat-race retry loop: once a
     * DataIntegrityViolationException has been caught, the persistence
     * context is poisoned and any further load fails. Resolving the name
     * before the race and passing it in keeps the lazy read on a clean
     * session. (Found by concurrentBookings_neverExceedCapacity: retry
     * winners were returning 500 instead of 201, so only the one racer that
     * never collided got a seat.)
     */
    private ReservationResponse toResponse(Reservation reservation, Queue queue, String businessName) {
        return new ReservationResponse(
                reservation.getReservationToken(),
                businessName,
                queue.getName(),
                reservation.getSlotStart(),
                queue.getSlotMinutes(),
                reservation.getCustomerName(),
                reservation.getPartySize(),
                reservation.getStatus());
    }
}
