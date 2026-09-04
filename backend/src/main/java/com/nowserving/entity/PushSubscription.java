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
 * One device that agreed to receive notifications for one ticket.
 *
 * The three fields the browser hands us:
 *  - endpoint: a URL at the browser vendor's push service. To notify the
 *    customer we POST to this URL — we never reach the phone ourselves.
 *  - p256dh + auth: the device's public key and a shared secret. We encrypt
 *    the payload with them, so the push service relays a message it cannot
 *    read. That end-to-end encryption is why Web Push needs no trust in
 *    Google/Mozilla beyond delivery.
 */
@Entity
@Table(name = "push_subscriptions")
@Getter
@Setter
@NoArgsConstructor
public class PushSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entry_id", nullable = false)
    private QueueEntry entry;

    @Column(nullable = false, columnDefinition = "text")
    private String endpoint;

    @Column(nullable = false)
    private String p256dh;

    @Column(nullable = false)
    private String auth;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public PushSubscription(QueueEntry entry, String endpoint, String p256dh, String auth) {
        this.entry = entry;
        this.endpoint = endpoint;
        this.p256dh = p256dh;
        this.auth = auth;
    }
}
