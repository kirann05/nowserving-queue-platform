package com.nowserving.repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.nowserving.entity.PushSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, Long> {

    /** Every device registered for one ticket (phone + laptop, say). */
    List<PushSubscription> findByEntryId(Long entryId);

    /** Re-subscribing the same device must update, not duplicate. */
    Optional<PushSubscription> findByEntryIdAndEndpoint(Long entryId, String endpoint);

    /**
     * Push services return 404/410 when a subscription is dead (browser
     * uninstalled, permission revoked). Deleting on that signal keeps us from
     * hammering endpoints that will never work again.
     */
    void deleteByEndpoint(String endpoint);

    /** Business teardown, step 1. */
    @Modifying
    @Query("delete from PushSubscription p where p.entry.queue.business.id = :businessId")
    void deleteByBusinessId(@Param("businessId") Long businessId);
}
