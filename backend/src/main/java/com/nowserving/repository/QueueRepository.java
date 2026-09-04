package com.nowserving.repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.nowserving.entity.Queue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface QueueRepository extends JpaRepository<Queue, Long> {

    /** All queues of ONE business — the staff dashboard list. */
    List<Queue> findByBusinessIdOrderByCreatedAtAsc(Long businessId);

    /**
     * THE tenant-isolation query (NS-4). Fetching by (id AND businessId) means
     * asking for someone else's queue simply finds nothing -> 404. The scoping
     * lives in the query itself, so there is no "load first, remember to check
     * ownership afterwards" step to forget.
     */
    Optional<Queue> findByIdAndBusinessId(Long id, Long businessId);

    /** Public lookup by the unguessable token in the join link. */
    Optional<Queue> findByJoinToken(String joinToken);

    /**
     * V9 — the discovery page's one query.
     *
     * `join fetch q.business` is doing real work here: without it, rendering
     * 20 cards means 1 query for the queues plus 20 more for their business
     * names — the N+1 problem, the single most common cause of a page that
     * is fast with test data and miserable in production. One join, one query.
     *
     * Matching is on the business OR queue name, case-insensitively, so
     * "spicy" finds "Spicy Kitchen" and "dinner" finds its dinner line. An
     * empty search returns everything listed, which is what the "nearby"
     * view wants.
     */
    @org.springframework.data.jpa.repository.Query("""
            select q from Queue q
              join fetch q.business b
             where q.listedPublicly = true
               and (:q is null or :q = ''
                    or lower(b.name) like lower(concat('%', :q, '%'))
                    or lower(q.name) like lower(concat('%', :q, '%')))
            """)
    List<Queue> findListed(@org.springframework.data.repository.query.Param("q") String q);

    /** Business teardown, step 4. */
    @Modifying
    @Query("delete from Queue q where q.business.id = :businessId")
    void deleteByBusinessId(@Param("businessId") Long businessId);
}
