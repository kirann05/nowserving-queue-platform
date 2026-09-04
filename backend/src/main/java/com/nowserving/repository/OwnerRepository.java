package com.nowserving.repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.nowserving.entity.Owner;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OwnerRepository extends JpaRepository<Owner, Long> {

    /**
     * A "derived query": Spring Data parses the METHOD NAME and generates
     * `... WHERE email = ?`. No SQL written, but the name is a contract —
     * rename the field and the query breaks (at startup, thankfully).
     */
    Optional<Owner> findByEmail(String email);

    boolean existsByEmail(String email);

    /** Google sign-in lookup (FR-1): match on Google's permanent id, not email. */
    Optional<Owner> findByGoogleSub(String googleSub);

    /** Business teardown, step 5 — every login that could reach the data. */
    @Modifying
    @Query("delete from Owner o where o.business.id = :businessId")
    void deleteByBusinessId(@Param("businessId") Long businessId);
}
