package com.nowserving.service;

import com.nowserving.dto.PublicDtos.PositionResponse;
import com.nowserving.entity.EntryStatus;
import com.nowserving.entity.Queue;
import com.nowserving.entity.QueueEntry;
import com.nowserving.realtime.RealtimePublisher;
import com.nowserving.repository.QueueEntryRepository;
import com.nowserving.repository.QueueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Map;

/**
 * Turns a domain event ("queue X changed") into WebSocket pushes.
 *
 * @TransactionalEventListener (default phase AFTER_COMMIT) is the key line in
 * this class: the broadcast fires only after the database transaction that
 * published the event has COMMITTED. Two reasons that matters:
 *  1. Correctness — if the transaction rolls back, customers must never have
 *     seen a position that officially never happened.
 *  2. Freshness — the positions we compute here read the committed state, so
 *     every subscriber gets the same truth the REST API would return.
 *
 * Cost note: one advance = one query for the waiting list + one message per
 * waiting customer. At MVP scale (tens of people per queue) that's nothing.
 * At 10k-person queues you'd switch to a queue-wide diff message — a
 * deliberate "later" problem.
 */
@Component
@RequiredArgsConstructor
public class QueueEventsBroadcaster {

    private final QueueEntryRepository entryRepository;
    private final QueueRepository queueRepository;
    private final WaitEstimator waitEstimator;
    /**
     * Sprint 2, part two: we publish to the Redis BUS, not straight to this
     * instance's sockets. Every instance (including this one) hears it and
     * delivers to whichever browsers it happens to be holding — so live
     * updates keep working when the app runs as more than one process.
     * See RealtimePublisher for the bug this prevents.
     */
    private final RealtimePublisher realtimePublisher;

    @TransactionalEventListener
    public void onQueueChanged(QueueChangedEvent event) {
        // 0. The customer who just left the line (served / no-show), if any.
        if (event.affectedEntryToken() != null) {
            realtimePublisher.publish(
                    "/topic/entries/" + event.affectedEntryToken(),
                    new PositionResponse(event.affectedStatus(), null, null, null,
                            null, null, businessNameOf(event.queueId())));
        }

        // 1. Ping staff dashboards: "refetch the line". No data in the
        //    payload on purpose — queue ids are guessable, so this topic
        //    must not leak customer names.
        realtimePublisher.publish(
                "/topic/queues/" + event.queueId(),
                Map.of("type", "LINE_CHANGED"));

        // 2. Push every waiting customer their fresh position — the exact
        //    same PositionResponse shape the REST endpoint returns, so the
        //    frontend can reuse one rendering path for both transports.
        Queue queue = queueRepository.findById(event.queueId()).orElse(null);
        if (queue == null) return;

        List<QueueEntry> waiting = entryRepository
                .findByQueueIdAndStatusOrderByQueueOrderAtAscIdAsc(event.queueId(), EntryStatus.WAITING);

        for (int i = 0; i < waiting.size(); i++) {
            QueueEntry entry = waiting.get(i);
            int position = i + 1; // list is already in line order — no COUNT per entry
            int peopleAhead = position - 1;
            // Same estimator the REST endpoint uses, so a pushed number and a
            // polled number can never disagree.
            int estimated = waitEstimator.estimateMinutes(queue, peopleAhead);
            realtimePublisher.publish(
                    "/topic/entries/" + entry.getEntryToken(),
                    // V9: businessName travels on the PUSH too, not just the
                    // REST read. The ticket page takes whichever transport
                    // spoke last, so a field present in one and absent in the
                    // other makes the venue's name flicker away the moment
                    // the line moves — a bug that only shows up live.
                    new PositionResponse(EntryStatus.WAITING, position, peopleAhead, estimated,
                            null, null, queue.getBusiness().getName()));
        }
    }

    private String businessNameOf(Long queueId) {
        return queueRepository.findById(queueId)
                .map(q -> q.getBusiness().getName())
                .orElse(null);
    }
}
