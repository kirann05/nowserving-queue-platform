package com.nowserving.service;

import com.nowserving.dto.QueueDtos.AdvanceResponse;
import com.nowserving.dto.QueueDtos.CreateQueueRequest;
import com.nowserving.dto.QueueDtos.QueueResponse;
import com.nowserving.dto.QueueDtos.WaitingEntryResponse;
import com.nowserving.dto.QueueDtos.HistoryEntryResponse;
import com.nowserving.dto.QueueDtos.QueueStatsResponse;
import com.nowserving.dto.QueueDtos.QueueStatusRequest;
import com.nowserving.entity.QueueStatus;
import com.nowserving.dto.QueueDtos.VenueConfigRequest;
import com.nowserving.dto.QueueDtos.VenueConfigResponse;
import com.nowserving.entity.Business;
import com.nowserving.entity.EntryStatus;
import com.nowserving.entity.Queue;
import com.nowserving.entity.QueueEntry;
import com.nowserving.entity.ServiceSample;
import com.nowserving.exception.BadRequestException;
import com.nowserving.exception.NotFoundException;
import com.nowserving.repository.BusinessRepository;
import com.nowserving.repository.QueueEntryRepository;
import com.nowserving.repository.QueueRepository;
import com.nowserving.repository.ReservationRepository;
import com.nowserving.repository.ServiceSampleRepository;
import com.nowserving.dto.ReservationDtos.BookingConfigRequest;
import com.nowserving.dto.ReservationDtos.BookingConfigResponse;
import com.nowserving.dto.ReservationDtos.ReservationSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Owner/staff-side queue operations: NS-4 (create/list/detail),
 * NS-7 (view the line), NS-8 (advance, no-show).
 *
 * Every public method here takes businessId as its first parameter — always
 * the value from the caller's JWT, never from client input. Tenant isolation
 * is enforced on every single query; there is no code path that loads a queue
 * without proving ownership in the same breath.
 */
@Service
@RequiredArgsConstructor
public class QueueService {

    /** Gaps longer than this mean "the shop was idle", not "a slow haircut". */
    private static final long MAX_CREDIBLE_SERVICE_SECONDS = Duration.ofHours(2).getSeconds();

    private final QueueRepository queueRepository;
    private final QueueEntryRepository entryRepository;
    private final BusinessRepository businessRepository;
    private final ServiceSampleRepository sampleRepository;
    private final ReservationRepository reservationRepository;
    /** Sprint 2: publishing here + @TransactionalEventListener on the other
     *  side = "broadcast over WebSocket, but only if this commit succeeds". */
    private final ApplicationEventPublisher eventPublisher;

    @Value("${app.public-base-url}")
    private String publicBaseUrl;

    // ---------- NS-4: create / list / detail ----------

    @Transactional
    public QueueResponse create(Long businessId, CreateQueueRequest request) {
        // getReferenceById returns a lazy proxy WITHOUT a SELECT — we only
        // need the FK value for the INSERT, and the JWT already proved this
        // business exists. (findById would work too, at the cost of a query.)
        Business business = businessRepository.getReferenceById(businessId);

        Queue queue = new Queue(
                business,
                request.name(),
                // Nulls mean "not specified" -> fall back to sensible defaults
                // (mirroring the column DEFAULTs in V1__init.sql).
                request.stationCount() != null ? request.stationCount() : 1,
                request.defaultServiceMinutes() != null ? request.defaultServiceMinutes() : 15);
        return toResponse(queueRepository.save(queue));
    }

    @Transactional(readOnly = true)
    public List<QueueResponse> list(Long businessId) {
        return queueRepository.findByBusinessIdOrderByCreatedAtAsc(businessId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public QueueResponse get(Long businessId, Long queueId) {
        return toResponse(requireOwnedQueue(businessId, queueId));
    }

    // ---------- NS-7: the staff view ----------

    @Transactional(readOnly = true)
    public List<WaitingEntryResponse> waitingEntries(Long businessId, Long queueId) {
        Queue queue = requireOwnedQueue(businessId, queueId); // tenant check FIRST
        List<QueueEntry> waiting =
                entryRepository.findByQueueIdAndStatusOrderByQueueOrderAtAscIdAsc(queue.getId(), EntryStatus.WAITING);

        Instant now = Instant.now();
        // The list is already in queue order, so position is just index + 1 —
        // one query for the whole dashboard, no per-row counting.
        return java.util.stream.IntStream.range(0, waiting.size())
                .mapToObj(i -> toWaitingResponse(waiting.get(i), i + 1, now))
                .toList();
    }

    // ---------- NS-8: advance the line ⭐ ----------

    /**
     * The sprint's most important method. The scary scenario: two staff
     * tablets tap "Next" at the same instant. Naive code —
     * find the front entry, then mark it served — has a race window between
     * the read and the write, and both requests would serve the SAME customer
     * while the second-in-line gets skipped.
     *
     * The fix lives in {@link QueueEntryRepository#lockNextWaiting}: the
     * SELECT itself takes a row lock (FOR UPDATE), and SKIP LOCKED makes a
     * concurrent caller leapfrog to the next free row instead of waiting.
     * Both taps lock DIFFERENT rows; two different people get served. The
     * lock releases when this @Transactional method commits.
     *
     * AdvanceConcurrencyTest proves this by firing simultaneous advances at a
     * real Postgres.
     */
    @Transactional
    public AdvanceResponse advance(Long businessId, Long queueId) {
        Queue queue = requireOwnedQueue(businessId, queueId);

        QueueEntry front = entryRepository.lockNextWaiting(queue.getId())
                .orElseThrow(() -> new BadRequestException("No one is waiting in this queue"));

        Instant now = Instant.now();
        front.transitionTo(EntryStatus.SERVED); // state-machine-guarded
        front.setServedAt(now);

        // Sprint 3: learn how long serving actually takes, from the gap since
        // the previous advance. Guarded two ways — no sample on the very
        // first advance (no gap to measure), and none for absurdly long gaps,
        // which mean the shop was simply idle rather than serving someone
        // slowly. The median downstream mops up whatever noise is left.
        if (queue.getLastAdvanceAt() != null) {
            long gapSeconds = Duration.between(queue.getLastAdvanceAt(), now).getSeconds();
            if (gapSeconds > 0 && gapSeconds <= MAX_CREDIBLE_SERVICE_SECONDS) {
                sampleRepository.save(new ServiceSample(queue, (int) gapSeconds, now));
            }
        }
        queue.setLastAdvanceAt(now);
        // No explicit save() needed: 'front' is a MANAGED entity inside a
        // transaction — Hibernate's dirty checking flushes the UPDATE at
        // commit. (An explicit save() would be harmless, just redundant.)

        // Who's next now? Excludes 'front' because its in-memory status is
        // already SERVED and the flush happens before this query runs.
        WaitingEntryResponse nextUp = entryRepository
                .findFirstByQueueIdAndStatusOrderByQueueOrderAtAscIdAsc(queue.getId(), EntryStatus.WAITING)
                .map(e -> toWaitingResponse(e, 1, Instant.now()))
                .orElse(null);

        // Fires AFTER this transaction commits (see QueueEventsBroadcaster):
        // the served customer hears SERVED, everyone behind gets a fresh
        // position, staff dashboards get a "line changed" ping.
        eventPublisher.publishEvent(new QueueChangedEvent(
                queue.getId(), front.getEntryToken(), EntryStatus.SERVED));

        return new AdvanceResponse(
                new AdvanceResponse.ServedEntry(front.getId(), front.getCustomerName(),
                        front.getPartySize(), front.getServedAt()),
                nextUp);
    }

    // ---------- P0: open / close the queue ----------

    /**
     * PATCH /queues/{id}/status. Explicit, audited, and idempotent: setting
     * the state it's already in is a harmless no-op (a double-tapped button
     * must not be an error). Audit fields answer the inevitable "who closed
     * my queue at 3pm?" without a forensic log dive.
     */
    @Transactional
    public QueueResponse changeStatus(Long businessId, Long queueId, String changedByEmail,
                                      QueueStatusRequest request) {
        Queue queue = requireOwnedQueue(businessId, queueId);
        if (queue.getStatus() != request.status()) {
            queue.setStatus(request.status());
            if (request.status() == QueueStatus.CLOSED) {
                queue.setClosedAt(Instant.now());
                queue.setClosedBy(changedByEmail);
                queue.setCloseReason(request.reason());
            } else {
                queue.setClosedAt(null);
                queue.setClosedBy(null);
                queue.setCloseReason(null);
            }
            // Staff dashboards flip their badge live.
            eventPublisher.publishEvent(QueueChangedEvent.of(queue.getId()));
        }
        return toResponse(queue);
    }

    // ---------- P1: history + stats ----------

    /** The ?status= filter, actually filtering (it used to 400 on anything but WAITING). */
    @Transactional(readOnly = true)
    public List<HistoryEntryResponse> history(Long businessId, Long queueId,
                                              EntryStatus status, LocalDate date) {
        Queue queue = requireOwnedQueue(businessId, queueId);
        ZoneId zone = ZoneId.of(queue.getTimeZone());
        LocalDate day = date != null ? date : LocalDate.now(zone);
        Instant from = day.atStartOfDay(zone).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(zone).toInstant();

        return entryRepository.findHistory(queue.getId(), status, from, to).stream()
                .map(e -> new HistoryEntryResponse(
                        e.getId(), e.getCustomerName(), e.getPartySize(), e.getStatus(),
                        e.getJoinedAt(), e.getServedAt(),
                        e.getServedAt() == null ? null
                                : Duration.between(e.getJoinedAt(), e.getServedAt()).toMinutes(),
                        e.getRatingStars(), e.getFeedbackComment()))
                .toList();
    }

    @Transactional(readOnly = true)
    public QueueStatsResponse stats(Long businessId, Long queueId) {
        Queue queue = requireOwnedQueue(businessId, queueId);
        ZoneId zone = ZoneId.of(queue.getTimeZone());
        Instant from = LocalDate.now(zone).atStartOfDay(zone).toInstant();
        Instant to = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant();

        Double avgSeconds = entryRepository.avgWaitSeconds(queue.getId(), from, to);
        Double medianSeconds = entryRepository.medianWaitSeconds(queue.getId(), from, to);
        Double paceSeconds = sampleRepository.countByQueueId(queue.getId()) >= 5
                ? sampleRepository.medianRecentDurationSeconds(queue.getId(), 20) : null;
        Long longestWait = entryRepository
                .findFirstByQueueIdAndStatusOrderByQueueOrderAtAscIdAsc(queue.getId(), EntryStatus.WAITING)
                .map(front -> Duration.between(front.getJoinedAt(), Instant.now()).toMinutes())
                .orElse(null);

        return new QueueStatsResponse(
                queue.getStatus(),
                entryRepository.countByQueueIdAndStatus(queue.getId(), EntryStatus.WAITING),
                entryRepository.countByQueueIdAndStatusAndServedAtBetween(
                        queue.getId(), EntryStatus.SERVED, from, to),
                avgSeconds == null ? null : Math.round(avgSeconds / 60.0),
                medianSeconds == null ? null : Math.round(medianSeconds / 60.0),
                longestWait,
                reservationRepository.findByQueueIdAndSlotStartBetweenOrderBySlotStartAscSeatNoAsc(
                        queue.getId(), from, to).size(),
                entryRepository.countByQueueIdAndStatusAndJoinedAtBetween(
                        queue.getId(), EntryStatus.NO_SHOW, from, to),
                paceSeconds == null ? null : Math.round(paceSeconds / 60.0));
    }

    // ---------- Sprint 4: booking configuration (FR-10) ----------

    /** Owner sets opening hours, slot length and capacity for this queue. */
    @Transactional
    public BookingConfigResponse configureBooking(Long businessId, Long queueId,
                                                  BookingConfigRequest request) {
        Queue queue = requireOwnedQueue(businessId, queueId);

        queue.setReservationsEnabled(Boolean.TRUE.equals(request.reservationsEnabled()));
        if (request.openingTime() != null) queue.setOpeningTime(request.openingTime());
        if (request.closingTime() != null) queue.setClosingTime(request.closingTime());
        if (request.slotMinutes() != null) queue.setSlotMinutes(request.slotMinutes());
        if (request.slotCapacity() != null) queue.setSlotCapacity(request.slotCapacity());
        if (request.timeZone() != null) {
            try {
                ZoneId.of(request.timeZone()); // reject "EST5EDT-ish" typos at the door
            } catch (Exception e) {
                throw new BadRequestException("Unknown time zone: " + request.timeZone());
            }
            queue.setTimeZone(request.timeZone());
        }
        if (queue.isReservationsEnabled()
                && (queue.getOpeningTime() == null || queue.getClosingTime() == null)) {
            throw new BadRequestException("Set opening and closing times before enabling reservations");
        }
        return toBookingConfig(queue);
    }

    @Transactional(readOnly = true)
    public BookingConfigResponse bookingConfig(Long businessId, Long queueId) {
        return toBookingConfig(requireOwnedQueue(businessId, queueId));
    }

    @Transactional(readOnly = true)
    public List<ReservationSummary> reservationsForDay(Long businessId, Long queueId, LocalDate date) {
        Queue queue = requireOwnedQueue(businessId, queueId); // tenant check first, always
        ZoneId zone = ZoneId.of(queue.getTimeZone());
        Instant from = date.atStartOfDay(zone).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(zone).toInstant();

        return reservationRepository
                .findByQueueIdAndSlotStartBetweenOrderBySlotStartAscSeatNoAsc(queue.getId(), from, to)
                .stream()
                .map(r -> new ReservationSummary(r.getId(), r.getSlotStart(),
                        r.getCustomerName(), r.getPartySize(), r.getStatus()))
                .toList();
    }

    private BookingConfigResponse toBookingConfig(Queue queue) {
        return new BookingConfigResponse(
                queue.isReservationsEnabled(), queue.getOpeningTime(), queue.getClosingTime(),
                queue.getSlotMinutes(), queue.getSlotCapacity(), queue.getTimeZone());
    }

    /** DELETE /entries/{id} — staff removes a no-show (NS-8). */
    @Transactional
    public void markNoShow(Long businessId, Long entryId) {
        // Tenant-scoped lookup: an entry id from another business is a 404,
        // exactly like queues.
        QueueEntry entry = entryRepository.findByIdAndQueue_Business_Id(entryId, businessId)
                .orElseThrow(() -> new NotFoundException("Entry not found"));

        entry.transitionTo(EntryStatus.NO_SHOW); // throws 400 unless WAITING/CALLED

        eventPublisher.publishEvent(new QueueChangedEvent(
                entry.getQueue().getId(), entry.getEntryToken(), EntryStatus.NO_SHOW));
    }

    // ---------- helpers ----------

    /** The one place the "is this queue yours?" question is asked. */
    private Queue requireOwnedQueue(Long businessId, Long queueId) {
        return queueRepository.findByIdAndBusinessId(queueId, businessId)
                // 404, not 403 — see NotFoundException's comment on why.
                .orElseThrow(() -> new NotFoundException("Queue not found"));
    }

    /** Enough to recognise who's in a line without turning the dashboard
     *  card into the queue page. */
    private static final int DASHBOARD_NAME_PREVIEW = 3;

    private QueueResponse toResponse(Queue queue) {
        return new QueueResponse(
                queue.getId(),
                queue.getName(),
                queue.getStatus(),
                queue.getStationCount(),
                queue.getDefaultServiceMinutes(),
                queue.getJoinToken(),
                // The link the owner shares/prints. The /j/{token} page is the
                // (future) customer frontend; the API behind it already works.
                publicBaseUrl + "/j/" + queue.getJoinToken(),
                entryRepository.countByQueueIdAndStatus(queue.getId(), EntryStatus.WAITING),
                // Same query and ordering the queue page itself uses, so the
                // dashboard can never name someone the detail page doesn't.
                entryRepository
                        .findByQueueIdAndStatusOrderByQueueOrderAtAscIdAsc(queue.getId(), EntryStatus.WAITING)
                        .stream()
                        .limit(DASHBOARD_NAME_PREVIEW)
                        .map(QueueEntry::getCustomerName)
                        .toList(),
                queue.isListedPublicly(),
                queue.getCreatedAt());
    }

    private WaitingEntryResponse toWaitingResponse(QueueEntry entry, int position, Instant now) {
        return new WaitingEntryResponse(
                entry.getId(),
                entry.getCustomerName(),
                entry.getPartySize(),
                position,
                entry.getJoinedAt(),
                Duration.between(entry.getJoinedAt(), now).toMinutes(),
                // FR-15/FR-16: staff see that someone is travelling and whether
                // their spot is being held — but never where they are.
                entry.getEnRouteAt() != null,
                entry.getGraceExpiresAt());
    }

    // ---------- Sprint 5: venue location + late policy ----------

    @Transactional
    public VenueConfigResponse configureVenue(Long businessId, Long queueId, VenueConfigRequest request) {
        Queue queue = requireOwnedQueue(businessId, queueId);
        if (request.venueLatitude() != null) queue.setVenueLatitude(request.venueLatitude());
        if (request.venueLongitude() != null) queue.setVenueLongitude(request.venueLongitude());
        if (request.graceMinutes() != null) queue.setGraceMinutes(request.graceMinutes());
        if (request.bumpPlaces() != null) queue.setBumpPlaces(request.bumpPlaces());
        if (request.safetyBufferMinutes() != null) {
            queue.setSafetyBufferMinutes(request.safetyBufferMinutes());
        }
        // V9 — discovery + remote-join policy. Same null-means-untouched rule.
        if (request.listedPublicly() != null) queue.setListedPublicly(request.listedPublicly());
        if (request.allowRemoteJoin() != null) queue.setAllowRemoteJoin(request.allowRemoteJoin());
        if (request.allowQrJoin() != null) queue.setAllowQrJoin(request.allowQrJoin());
        if (request.maxRemoteJoinMiles() != null) {
            // Belt and braces with the @Max(50) on the DTO: services are also
            // called from tests and future callers that never touched a
            // controller, so the invariant is enforced where it actually lives.
            queue.setMaxRemoteJoinMiles(
                    Math.min(request.maxRemoteJoinMiles(), Queue.MAX_REMOTE_JOIN_MILES_CEILING));
        }
        return toVenueConfig(queue);
    }

    @Transactional(readOnly = true)
    public VenueConfigResponse venueConfig(Long businessId, Long queueId) {
        return toVenueConfig(requireOwnedQueue(businessId, queueId));
    }

    private VenueConfigResponse toVenueConfig(Queue queue) {
        return new VenueConfigResponse(queue.getVenueLatitude(), queue.getVenueLongitude(),
                queue.getGraceMinutes(), queue.getBumpPlaces(), queue.getSafetyBufferMinutes(),
                queue.isListedPublicly(), queue.isAllowRemoteJoin(),
                queue.getMaxRemoteJoinMiles(), queue.isAllowQrJoin(),
                Queue.MAX_REMOTE_JOIN_MILES_CEILING);
    }
}
