package com.nowserving.service;

import com.nowserving.entity.Queue;
import com.nowserving.entity.Reservation;
import com.nowserving.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Inserts one reservation row in its OWN transaction.
 *
 * Two reasons this is a separate bean, and both are traps worth remembering:
 *
 * 1. SELF-INVOCATION. @Transactional works through a Spring proxy. If
 *    ReservationService called {@code this.insert(...)}, the call would never
 *    pass through that proxy and REQUIRES_NEW would silently do nothing.
 *
 * 2. RETRY NEEDS A FRESH TRANSACTION. When the UNIQUE seat constraint fires,
 *    the current transaction is marked rollback-only — nothing further can be
 *    done in it. Each attempt therefore needs its own transaction so the next
 *    seat can be tried cleanly.
 */
@Service
@RequiredArgsConstructor
public class ReservationSeatWriter {

    private final ReservationRepository reservationRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Reservation insert(Queue queue, Instant slotStart, int seatNo,
                              String customerName, String contact, int partySize,
                              String idempotencyKey) {
        Reservation reservation = new Reservation(
                queue, slotStart, seatNo, customerName, contact, partySize, idempotencyKey);
        // saveAndFlush, not save: we need the INSERT (and therefore the
        // constraint check) to happen HERE, not at some later commit where we
        // can no longer react to it.
        return reservationRepository.saveAndFlush(reservation);
    }
}
