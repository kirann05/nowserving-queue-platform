package com.nowserving.repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.nowserving.entity.TrackingConsent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Consent records for location sharing (FR-11).
 *
 * A top-level interface, not a nested one: Spring Data's scanner looks for
 * interfaces extending Repository at the package level, and interfaces tucked
 * inside another class are silently not registered — which surfaces as a
 * baffling "no qualifying bean" at startup rather than a compile error.
 */
public interface TrackingConsentRepository extends JpaRepository<TrackingConsent, Long> {

    /** The active (un-revoked) consent for a ticket, if there is one. */
    Optional<TrackingConsent> findFirstByEntryIdAndRevokedAtIsNull(Long entryId);

    /** Business teardown, step 1. */
    @Modifying
    @Query("delete from TrackingConsent t where t.entry.queue.business.id = :businessId")
    void deleteByBusinessId(@Param("businessId") Long businessId);
}
