package com.nowserving.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A record of every travel-related thing we told a customer (FR-13/FR-14).
 *
 * This table is the anti-spam memory. Before sending anything the engine asks
 * "have I already said this, and how recently?" — without it, a scheduler
 * running every minute would send sixty identical "leave now" alerts an hour
 * and the customer would turn notifications off forever.
 */
@Entity
@Table(name = "leave_now_alerts")
@Getter
@Setter
@NoArgsConstructor
public class LeaveNowAlert {

    public enum AlertType {
        /** The first "head over now". */
        LEAVE_NOW,
        /** A single follow-up when conditions changed materially (FR-14). */
        CORRECTION
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entry_id", nullable = false)
    private QueueEntry entry;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "alert_type", nullable = false)
    private AlertType alertType;

    /** What we believed the journey was when we sent it — lets a later run
     *  decide whether reality has drifted enough to be worth a correction. */
    @Column(name = "computed_travel_minutes", nullable = false)
    private int computedTravelMinutes;

    public LeaveNowAlert(QueueEntry entry, Instant sentAt, AlertType alertType, int computedTravelMinutes) {
        this.entry = entry;
        this.sentAt = sentAt;
        this.alertType = alertType;
        this.computedTravelMinutes = computedTravelMinutes;
    }
}
