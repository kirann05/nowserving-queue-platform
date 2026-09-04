package com.nowserving.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A single line customers can join — "Shiva's Barbers, walk-ins".
 * A business can have several (walk-ins vs. beard trims, or one per branch).
 */
@Entity
@Table(name = "queues")
@Getter
@Setter
@NoArgsConstructor
public class Queue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;

    @Column(nullable = false)
    private String name;

    /**
     * The unguessable token in the public join link (/j/{joinToken}).
     * We expose THIS to the world, never the sequential numeric id — otherwise
     * anyone could enumerate /queues/1, /queues/2, ... (an IDOR vulnerability).
     */
    @Column(name = "join_token", nullable = false, unique = true, updatable = false)
    private String joinToken;

    /**
     * EnumType.STRING stores "OPEN"/"CLOSED" text. The alternative — ORDINAL —
     * stores 0/1 and silently corrupts your data the day someone reorders the
     * enum constants. Always STRING.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private QueueStatus status = QueueStatus.OPEN;

    /** How many customers are served in parallel (2 barber chairs = 2). */
    @Column(name = "station_count", nullable = false)
    private int stationCount = 1;

    /** Owner-configured minutes per customer; feeds the NS-6 wait estimate. */
    @Column(name = "default_service_minutes", nullable = false)
    private int defaultServiceMinutes = 15;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * When staff last advanced this queue. The gap between two advances is
     * our measurement of how long one customer takes to serve (Sprint 3) —
     * see V3__samples_and_push.sql.
     */
    @Column(name = "last_advance_at")
    private Instant lastAdvanceAt;

    // ---- Sprint 4: booking configuration (FR-10) ----

    @Column(name = "reservations_enabled", nullable = false)
    private boolean reservationsEnabled = false;

    /** Wall-clock local opening/closing, interpreted in {@link #timeZone}. */
    @Column(name = "opening_time")
    private LocalTime openingTime;

    @Column(name = "closing_time")
    private LocalTime closingTime;

    @Column(name = "slot_minutes", nullable = false)
    private int slotMinutes = 30;

    /** How many people can book the same slot. */
    @Column(name = "slot_capacity", nullable = false)
    private int slotCapacity = 1;

    /** IANA zone id ("Europe/London"), never a fixed offset — DST matters. */
    @Column(name = "time_zone", nullable = false)
    private String timeZone = "UTC";

    // ---- Sprint 5: Leave-Now (FR-11..FR-16) ----

    /** Where the venue is — the destination every travel estimate targets. */
    @Column(name = "venue_latitude")
    private Double venueLatitude;

    @Column(name = "venue_longitude")
    private Double venueLongitude;

    /** How long a spot is held once a customer's turn arrives (FR-16). */
    @Column(name = "grace_minutes", nullable = false)
    private int graceMinutes = 5;

    /** After the grace period: push back this many places, or 0 = no-show. */
    @Column(name = "bump_places", nullable = false)
    private int bumpPlaces = 2;

    /** Padding on every leave-by time — deliberately errs early (§3.8.3). */
    @Column(name = "safety_buffer_minutes", nullable = false)
    private int safetyBufferMinutes = 5;

    // ---- V9: discovery + remote-join policy ----

    /**
     * Whether this queue appears in the public restaurant search. Opt-in by
     * intent: a queue existing is not the same as a queue wanting customers
     * it has never met.
     *
     * Defaults to FALSE. A newly created queue — very possibly still being
     * tested by its owner — must never be one Save-tap away from strangers
     * finding it in search. The owner explicitly publishes when ready (see
     * QueueService.configureVenue's listedPublicly handling), which is also
     * why V9's column default of TRUE was changed here rather than only in
     * this field: existing demo rows keep working, but every queue created
     * from today forward starts hidden.
     */
    @Column(name = "listed_publicly", nullable = false)
    private boolean listedPublicly = false;

    /** May somebody join without being here? The owner's call, not ours. */
    @Column(name = "allow_remote_join", nullable = false)
    private boolean allowRemoteJoin = true;

    /**
     * How far away "remote" is still allowed to be. The product caps this at
     * 50, but a small cafe can set 5 — the point of the setting is that a
     * 20-minute line shouldn't be held by someone 45 minutes away.
     */
    @Column(name = "max_remote_join_miles", nullable = false)
    private int maxRemoteJoinMiles = 25;

    /** Scanning the QR is itself proof of presence, so it has its own switch
     *  and stays open even when remote joining is off. */
    @Column(name = "allow_qr_join", nullable = false)
    private boolean allowQrJoin = true;

    /** The hard ceiling the API validates against. Named, not a literal, so
     *  the DTO, the service and the CHECK constraint can't drift apart. */
    public static final int MAX_REMOTE_JOIN_MILES_CEILING = 50;

    // ---- close/reopen audit (V7) ----

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "closed_by")
    private String closedBy;

    @Column(name = "close_reason")
    private String closeReason;

    /** Leave-Now needs somewhere to send people. */
    public boolean hasVenueLocation() {
        return venueLatitude != null && venueLongitude != null;
    }

    /**
     * Inverse side of QueueEntry.queue — exists mainly to demonstrate
     * @OneToMany. mappedBy says "the FK lives on the other side"; this side is
     * read-only bookkeeping. In practice we rarely touch this collection:
     * loading "all entries ever" to find the 5 waiting ones is wasteful, so
     * services query QueueEntryRepository with precise conditions instead.
     */
    @OneToMany(mappedBy = "queue", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    private List<QueueEntry> entries = new ArrayList<>();

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
        if (this.joinToken == null) {
            this.joinToken = UUID.randomUUID().toString();
        }
    }

    public Queue(Business business, String name, int stationCount, int defaultServiceMinutes) {
        this.business = business;
        this.name = name;
        this.stationCount = stationCount;
        this.defaultServiceMinutes = defaultServiceMinutes;
    }
}
