package com.nowserving.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One observation of "how long it took to serve someone" in a queue.
 *
 * Measured as the gap between consecutive advances — see V3__samples_and_push.sql
 * for why that is the honest measure when there is no "start service" button.
 */
@Entity
@Table(name = "service_samples")
@Getter
@Setter
@NoArgsConstructor
public class ServiceSample {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "queue_id", nullable = false)
    private Queue queue;

    @Column(name = "duration_seconds", nullable = false)
    private int durationSeconds;

    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;

    public ServiceSample(Queue queue, int durationSeconds, Instant completedAt) {
        this.queue = queue;
        this.durationSeconds = durationSeconds;
        this.completedAt = completedAt;
    }
}
