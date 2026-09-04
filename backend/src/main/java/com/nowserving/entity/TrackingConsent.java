package com.nowserving.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * The audit trail of "this customer agreed to share their location" (FR-11).
 *
 * NOTICE WHAT IS NOT HERE: coordinates. Where somebody physically is lives in
 * Redis with a short TTL and nowhere else, so it expires on its own and never
 * reaches a backup, a read replica, or an analytics export. This table records
 * only that consent was given and when it was withdrawn — which is precisely
 * what you need to answer "prove I agreed" or a deletion request, and nothing
 * more. Collecting the minimum, and keeping it only as long as it is useful,
 * is called DATA MINIMISATION.
 */
@Entity
@Table(name = "tracking_consents")
@Getter
@Setter
@NoArgsConstructor
public class TrackingConsent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entry_id", nullable = false)
    private QueueEntry entry;

    @Column(name = "consent_given_at", nullable = false)
    private Instant consentGivenAt;

    /** Non-null once revoked — consent is not permanent (FR-11). */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    public TrackingConsent(QueueEntry entry, Instant consentGivenAt) {
        this.entry = entry;
        this.consentGivenAt = consentGivenAt;
    }

    public boolean isActive() {
        return revokedAt == null;
    }
}
