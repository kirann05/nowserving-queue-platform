package com.nowserving.repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.nowserving.entity.LeaveNowAlert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** What we've already told a customer about their journey (FR-13/FR-14). */
public interface LeaveNowAlertRepository extends JpaRepository<LeaveNowAlert, Long> {

    /** Most recent first — the anti-spam checks only care about the latest. */
    List<LeaveNowAlert> findByEntryIdOrderBySentAtDesc(Long entryId);

    /** Business teardown, step 1 — see BusinessService.deleteBusiness for why
     *  the order matters and why nothing here can rely on a DB cascade. */
    @Modifying
    @Query("delete from LeaveNowAlert a where a.entry.queue.business.id = :businessId")
    void deleteByBusinessId(@Param("businessId") Long businessId);
}
