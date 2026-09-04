package com.nowserving.repository;

import org.springframework.data.jpa.repository.Modifying;

import com.nowserving.entity.EntryStatus;
import com.nowserving.entity.QueueEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface QueueEntryRepository extends JpaRepository<QueueEntry, Long> {

    /** The staff view (NS-7): everyone waiting, in join order. The trailing
     *  IdAsc breaks ties if two people joined in the same microsecond. */
    List<QueueEntry> findByQueueIdAndStatusOrderByQueueOrderAtAscIdAsc(Long queueId, EntryStatus status);

    /** Customer's ticket lookup by their private token (NS-6). */
    Optional<QueueEntry> findByEntryToken(String entryToken);

    /** COUNT in the DB — never "load all rows and .size() them in Java". */
    long countByQueueIdAndStatus(Long queueId, EntryStatus status);

    /**
     * Tenant-scoped single-entry lookup for staff actions (NS-8's no-show).
     * The underscores navigate relationships: entry.queue.business.id —
     * same isolation idea as QueueRepository.findByIdAndBusinessId.
     */
    Optional<QueueEntry> findByIdAndQueue_Business_Id(Long id, Long businessId);

    /**
     * Position = how many WAITING entries are ahead of me, + 1 (NS-5/NS-6).
     * "Ahead" = joined earlier, or joined at the exact same instant with a
     * smaller id (the same tie-break as the ORDER BY everywhere else — the
     * count and the list must NEVER disagree about order).
     * This is JPQL (queries entities/fields), not SQL (tables/columns).
     */
    @Query("""
            SELECT COUNT(e) FROM QueueEntry e
            WHERE e.queue.id = :queueId
              AND e.status = com.nowserving.entity.EntryStatus.WAITING
              AND (e.queueOrderAt < :orderAt OR (e.queueOrderAt = :orderAt AND e.id < :id))
            """)
    long countWaitingAhead(@Param("queueId") Long queueId,
                           @Param("orderAt") Instant orderAt,
                           @Param("id") Long id);

    /**
     * NS-8's concurrency weapon. Native SQL because the magic is
     * Postgres-specific:
     *
     *   FOR UPDATE       -> row-level lock: "this row is mine until my
     *                       transaction commits" (pessimistic locking — the
     *                       same effect as JPA's @Lock(PESSIMISTIC_WRITE)).
     *   SKIP LOCKED      -> if another transaction already holds the front
     *                       row, don't wait for it — take the NEXT free row.
     *
     * So two staff tablets tapping "Next" simultaneously lock two DIFFERENT
     * rows and serve two different people. Without SKIP LOCKED, the second
     * transaction would block, then (this is the subtle bug) its
     * already-evaluated `LIMIT 1` can return an empty result instead of
     * re-scanning for the next person. This SELECT-FOR-UPDATE-SKIP-LOCKED
     * pattern is the textbook way to build job queues on Postgres.
     */
    @Query(value = """
            SELECT * FROM queue_entries
            WHERE queue_id = :queueId AND status = 'WAITING'
            ORDER BY queue_order_at ASC, id ASC
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<QueueEntry> lockNextWaiting(@Param("queueId") Long queueId);

    /**
     * Sprint 5 tiered polling: waiting customers at venues that have a
     * location configured, whose turn is near enough to be worth evaluating.
     *
     * The horizon filter is a COST control, not a correctness one — every row
     * this returns may trigger a paid routing call, so returning fewer rows is
     * money saved. Entries with no target time yet are included because a
     * fresh walk-in can still be minutes from the front.
     */
    @Query("""
            SELECT e FROM QueueEntry e
            WHERE e.status = :status
              AND e.queue.venueLatitude IS NOT NULL
              AND e.queue.venueLongitude IS NOT NULL
              AND (e.targetServeTime IS NULL OR e.targetServeTime <= :horizon)
            """)
    List<QueueEntry> findLeaveNowCandidates(@Param("status") EntryStatus status,
                                            @Param("horizon") Instant horizon);

    /** FR-16: anyone whose held spot has run out. */
    List<QueueEntry> findByStatusAndGraceExpiresAtBefore(EntryStatus status, Instant now);

    /** Used when bumping someone back N places (FR-16). */
    List<QueueEntry> findByQueueIdAndStatusAndQueueOrderAtAfterOrderByQueueOrderAtAscIdAsc(
            Long queueId, EntryStatus status, Instant after);

    /** P1 history view: completed entries in a period, newest completion first. */
    @Query("""
            SELECT e FROM QueueEntry e
            WHERE e.queue.id = :queueId AND e.status = :status
              AND ((e.servedAt IS NOT NULL AND e.servedAt >= :from AND e.servedAt < :to)
                   OR (e.servedAt IS NULL AND e.joinedAt >= :from AND e.joinedAt < :to))
            ORDER BY COALESCE(e.servedAt, e.joinedAt) DESC
            """)
    List<QueueEntry> findHistory(@Param("queueId") Long queueId,
                                 @Param("status") EntryStatus status,
                                 @Param("from") Instant from,
                                 @Param("to") Instant to);

    long countByQueueIdAndStatusAndServedAtBetween(Long queueId, EntryStatus status,
                                                   Instant from, Instant to);

    /** Average wait (seconds) for today's served customers — measured truth. */
    @Query(value = """
            SELECT AVG(EXTRACT(EPOCH FROM (served_at - joined_at)))
            FROM queue_entries
            WHERE queue_id = :queueId AND status = 'SERVED'
              AND served_at >= :from AND served_at < :to
            """, nativeQuery = true)
    Double avgWaitSeconds(@Param("queueId") Long queueId,
                          @Param("from") Instant from, @Param("to") Instant to);

    /** MEDIAN wait — the honest middle, immune to one customer's 4-hour outlier. */
    @Query(value = """
            SELECT percentile_cont(0.5) WITHIN GROUP (
                ORDER BY EXTRACT(EPOCH FROM (served_at - joined_at)))
            FROM queue_entries
            WHERE queue_id = :queueId AND status = 'SERVED'
              AND served_at >= :from AND served_at < :to
            """, nativeQuery = true)
    Double medianWaitSeconds(@Param("queueId") Long queueId,
                             @Param("from") Instant from, @Param("to") Instant to);

    /** No-shows/leavers today are dated by when they JOINED (they never got served_at). */
    long countByQueueIdAndStatusAndJoinedAtBetween(Long queueId, EntryStatus status,
                                                   Instant from, Instant to);

    /** Join idempotency: has this exact attempt already produced a ticket? */
    Optional<QueueEntry> findByQueueIdAndIdempotencyKey(Long queueId, String idempotencyKey);

    /** Un-locked peek at the front of the line (for "who's next" in responses). */
    Optional<QueueEntry> findFirstByQueueIdAndStatusOrderByQueueOrderAtAscIdAsc(Long queueId, EntryStatus status);

    /** Business teardown, step 2 — MUST run before reservations, because
     *  queue_entries.reservation_id points at them. */
    @Modifying
    @Query("delete from QueueEntry e where e.queue.business.id = :businessId")
    void deleteByBusinessId(@Param("businessId") Long businessId);
}
