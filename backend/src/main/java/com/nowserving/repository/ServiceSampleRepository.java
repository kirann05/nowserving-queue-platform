package com.nowserving.repository;

import org.springframework.data.jpa.repository.Modifying;

import com.nowserving.entity.ServiceSample;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ServiceSampleRepository extends JpaRepository<ServiceSample, Long> {

    /**
     * The MEDIAN of the most recent N samples — the heart of the Sprint 3
     * estimate (PRD §3.8.2 says median, explicitly, not mean).
     *
     * Why median: one customer whose card declined and who wandered off to
     * find an ATM produces a 240-minute sample. With services of
     * 10, 12, 11, 13, 240 the MEAN is 57 minutes (nonsense we'd show every
     * customer) while the MEDIAN is 12 (the truth). Medians are "robust to
     * outliers"; queue data is nothing but outliers.
     *
     * percentile_cont(0.5) is Postgres's built-in continuous percentile —
     * doing this in SQL means we never load thousands of rows into Java to
     * compute one number.
     *
     * Returns null when the queue has no samples yet (cold start).
     */
    @Query(value = """
            SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY s.duration_seconds)
            FROM (
                SELECT duration_seconds
                FROM service_samples
                WHERE queue_id = :queueId
                ORDER BY completed_at DESC
                LIMIT :sampleSize
            ) s
            """, nativeQuery = true)
    Double medianRecentDurationSeconds(@Param("queueId") Long queueId,
                                       @Param("sampleSize") int sampleSize);

    /** How many samples we have — drives the cold-start decision. */
    long countByQueueId(Long queueId);

    /** Business teardown, step 3. */
    @Modifying
    @Query("delete from ServiceSample s where s.queue.business.id = :businessId")
    void deleteByBusinessId(@Param("businessId") Long businessId);
}
