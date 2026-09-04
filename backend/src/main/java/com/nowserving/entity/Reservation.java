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

/** A booked seat in a future time slot (FR-10). */
@Entity
@Table(name = "reservations")
@Getter
@Setter
@NoArgsConstructor
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "queue_id", nullable = false)
    private Queue queue;

    /** The slot's start instant — and this booking's target serve time. */
    @Column(name = "slot_start", nullable = false)
    private Instant slotStart;

    /** 0-based seat within the slot; the UNIQUE constraint is what stops overbooking. */
    @Column(name = "seat_no", nullable = false)
    private int seatNo;

    @Column(name = "customer_name", nullable = false)
    private String customerName;

    private String contact;

    @Column(name = "party_size", nullable = false)
    private int partySize = 1;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReservationStatus status = ReservationStatus.BOOKED;

    @Column(name = "reservation_token", nullable = false, unique = true, updatable = false)
    private String reservationToken;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
        if (this.reservationToken == null) {
            this.reservationToken = UUID.randomUUID().toString();
        }
    }

    public Reservation(Queue queue, Instant slotStart, int seatNo,
                       String customerName, String contact, int partySize, String idempotencyKey) {
        this.queue = queue;
        this.slotStart = slotStart;
        this.seatNo = seatNo;
        this.customerName = customerName;
        this.contact = contact;
        this.partySize = partySize;
        this.idempotencyKey = idempotencyKey;
    }
}
