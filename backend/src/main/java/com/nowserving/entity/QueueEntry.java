package com.nowserving.entity;

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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One customer's ticket in one queue.
 *
 * Note what is NOT here: no position column. Position is DERIVED — "how many
 * WAITING entries joined before me, plus one". Storing it would mean rewriting
 * every row behind you on each advance (and getting it wrong under
 * concurrency). Deriving it means one COUNT query and it is always correct.
 */
@Entity
@Table(name = "queue_entries")
@Getter
@Setter
@NoArgsConstructor
public class QueueEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "queue_id", nullable = false)
    private Queue queue;

    @Column(name = "customer_name", nullable = false)
    private String customerName;

    @Column(name = "party_size", nullable = false)
    private int partySize = 1;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EntryStatus status = EntryStatus.WAITING;

    /** See V7: double-tap/retry protection on the public join. */
    @Column(name = "idempotency_key", updatable = false)
    private String idempotencyKey;

    /**
     * V9: optional SMS destination. Unlike the coarse coordinates in Redis,
     * this is durable identifying data — so it is only ever collected when
     * the customer picks SMS, and it dies with the ticket.
     */
    @Column(name = "phone_number")
    private String phoneNumber;

    /** V9: how this customer asked to be reached. Defaults to Web Push. */
    @Enumerated(EnumType.STRING)
    @Column(name = "notify_channel", nullable = false)
    private NotifyChannel notifyChannel = NotifyChannel.PUSH;

    /** 1–5 stars, given after being served (V8). Null = never asked/answered. */
    @Column(name = "rating_stars")
    private Integer ratingStars;

    /** Optional free text — where real product-improvement ideas arrive. */
    @Column(name = "feedback_comment")
    private String feedbackComment;

    @Column(name = "feedback_at")
    private Instant feedbackAt;

    /**
     * ALL status changes go through here, never through setStatus, so the
     * state machine in {@link EntryStatus} is actually enforced rather than
     * merely documented.
     */
    public void transitionTo(EntryStatus target) {
        if (!this.status.canTransitionTo(target)) {
            throw new com.nowserving.exception.BadRequestException(
                    "A %s ticket cannot become %s".formatted(this.status, target));
        }
        this.status = target;
    }

    /**
     * The customer's private handle on this ticket. Customers have no account
     * (joining must take < 15 seconds), so possession of this random token IS
     * their authentication. It goes only to the person who joined.
     */
    @Column(name = "entry_token", nullable = false, unique = true, updatable = false)
    private String entryToken;

    /**
     * When this person actually arrived. A FACT — never rewritten, which is
     * why it stays updatable = false. "Waited 40 minutes" is measured from it.
     */
    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    /**
     * What the line is SORTED by. Equal to joinedAt for everyone, until FR-16
     * bumps a late customer back — then only this moves. Splitting "when you
     * arrived" from "where you sit" is what lets us reorder the queue without
     * lying about history.
     */
    @Column(name = "queue_order_at", nullable = false)
    private Instant queueOrderAt;

    /** Set when staff calls this customer up ("you're up") — Sprint 2. */
    @Column(name = "called_at")
    private Instant calledAt;

    @Column(name = "served_at")
    private Instant servedAt;

    /**
     * When we first told this customer "you're next" (FR-8). Non-null means
     * "already buzzed" — the guard that makes notification IDEMPOTENT, so a
     * repeated event can't wake someone's phone twice for the same fact.
     */
    @Column(name = "next_notified_at")
    private Instant nextNotifiedAt;

    /**
     * THE SHARED ABSTRACTION (PRD §3.2, Sprint 4). "When is this person due?"
     *
     * A walk-in gets an estimate derived from the line; a redeemed
     * reservation gets its booked slot time. Everything downstream —
     * notifications, displays, and Sprint 5's leave-by maths — reads this one
     * field and never asks which front door the customer came through.
     */
    @Column(name = "target_serve_time")
    private Instant targetServeTime;

    /** Set when this entry came from a booking, so it can't be redeemed twice. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id")
    private Reservation reservation;

    /** FR-15: the customer confirmed they're travelling here. Staff see it. */
    @Column(name = "en_route_at")
    private Instant enRouteAt;

    /** FR-16: their turn came before they did; the spot is held until this. */
    @Column(name = "grace_expires_at")
    private Instant graceExpiresAt;

    @PrePersist
    void onCreate() {
        this.joinedAt = Instant.now();
        this.queueOrderAt = this.joinedAt; // identical until someone is bumped
        if (this.entryToken == null) {
            this.entryToken = UUID.randomUUID().toString();
        }
    }

    public QueueEntry(Queue queue, String customerName, int partySize) {
        this.queue = queue;
        this.customerName = customerName;
        this.partySize = partySize;
    }
}
