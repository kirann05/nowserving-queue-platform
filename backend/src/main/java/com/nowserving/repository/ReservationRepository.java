package com.nowserving.repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.nowserving.entity.Reservation;
import com.nowserving.entity.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    /** Which seats in this slot are already taken (used to pick the next free one). */
    List<Reservation> findByQueueIdAndSlotStartAndStatusNot(
            Long queueId, Instant slotStart, ReservationStatus status);

    /** Availability across a whole day, in one query rather than one per slot. */
    List<Reservation> findByQueueIdAndSlotStartBetweenAndStatusNot(
            Long queueId, Instant from, Instant to, ReservationStatus status);

    /** Idempotency: "have I already handled this exact booking attempt?" */
    Optional<Reservation> findByQueueIdAndIdempotencyKey(Long queueId, String idempotencyKey);

    /** The customer's own lookup, by their private token. */
    Optional<Reservation> findByReservationToken(String reservationToken);

    /** Staff view: today's bookings for a queue, in time order. */
    List<Reservation> findByQueueIdAndSlotStartBetweenOrderBySlotStartAscSeatNoAsc(
            Long queueId, Instant from, Instant to);

    /** Business teardown, step 3. */
    @Modifying
    @Query("delete from Reservation r where r.queue.business.id = :businessId")
    void deleteByBusinessId(@Param("businessId") Long businessId);
}
