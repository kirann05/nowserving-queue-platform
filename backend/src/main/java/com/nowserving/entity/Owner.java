package com.nowserving.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A human account that can log in and manage its business's queues.
 */
@Entity
@Table(name = "owners")
@Getter
@Setter
@NoArgsConstructor
public class Owner {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Owning side of the relationship: this entity holds the business_id FK.
     *
     * FetchType.LAZY is the default we want on EVERY @ManyToOne (the JPA spec's
     * default for @ManyToOne is EAGER — a historical mistake). With LAZY,
     * loading an Owner does NOT automatically join-fetch the Business; you get
     * a proxy that hits the DB only if you actually touch it. EAGER fetching
     * everywhere is how apps end up issuing 20 queries to render one page.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;

    @Column(nullable = false, unique = true)
    private String email;

    /**
     * BCrypt hash — the plaintext password is never stored anywhere.
     * NULL for owners who only ever sign in with Google (FR-1): they have no
     * password with us, so there is nothing to hash.
     */
    @Column(name = "password_hash")
    private String passwordHash;

    /**
     * Google's permanent unique id for this person (the ID token's "sub"
     * claim). NULL for email/password-only owners. We match on this rather
     * than email because email can change; sub cannot.
     */
    @Column(name = "google_sub", unique = true)
    private String googleSub;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public Owner(Business business, String email, String passwordHash, String displayName) {
        this.business = business;
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
    }
}
