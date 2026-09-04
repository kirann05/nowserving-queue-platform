package com.nowserving.service;

import com.nowserving.entity.Business;
import com.nowserving.exception.NotFoundException;
import com.nowserving.repository.BusinessRepository;
import com.nowserving.repository.LeaveNowAlertRepository;
import com.nowserving.repository.OwnerRepository;
import com.nowserving.repository.PushSubscriptionRepository;
import com.nowserving.repository.QueueEntryRepository;
import com.nowserving.repository.QueueRepository;
import com.nowserving.repository.ReservationRepository;
import com.nowserving.repository.ServiceSampleRepository;
import com.nowserving.repository.TrackingConsentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owner-facing settings for the business itself: rename it, or delete it and
 * everything it owns.
 *
 * TENANT ISOLATION: every method here takes businessId from the caller, and
 * every caller takes it from the signed JWT (AuthenticatedOwner) — never from
 * a request body. That is the same rule the rest of the app follows, and it
 * is why there is no "is this my business?" check to forget: an owner cannot
 * name a business that isn't theirs, because they cannot name one at all.
 */
@Service
@RequiredArgsConstructor
public class BusinessService {

    private final BusinessRepository businessRepository;
    private final OwnerRepository ownerRepository;
    private final QueueRepository queueRepository;
    private final QueueEntryRepository entryRepository;
    private final ReservationRepository reservationRepository;
    private final ServiceSampleRepository serviceSampleRepository;
    private final LeaveNowAlertRepository leaveNowAlertRepository;
    private final PushSubscriptionRepository pushSubscriptionRepository;
    private final TrackingConsentRepository trackingConsentRepository;

    /**
     * Rename the business. The name is what customers see on every discovery
     * card, venue page, ticket and reservation, so it is read back out of the
     * same row those all read — nothing is cached or duplicated.
     */
    @Transactional
    public Business rename(Long businessId, String rawName) {
        Business business = businessRepository.findById(businessId)
                .orElseThrow(() -> new NotFoundException("Business not found"));
        // Trim here as well as in bean validation: @NotBlank rejects "   ",
        // but "  Spicy Kitchen  " would otherwise be stored with its padding
        // and show up padded on every public card.
        business.setName(rawName.trim());
        return business;
    }

    /**
     * Permanently delete the business and every row that belongs to it.
     *
     * WHY HARD DELETE, AND WHY IN THIS EXACT ORDER
     *
     * Every foreign key in this schema is ON DELETE NO ACTION — there is no
     * database cascade anywhere (verified against information_schema, not
     * inferred from the entity annotations). JPA's only cascade is
     * Queue.entries, which would still leave leave_now_alerts,
     * push_subscriptions and tracking_consents pointing at rows it deleted.
     * So the order is written out explicitly, children before parents:
     *
     *   leave_now_alerts / push_subscriptions / tracking_consents  -> entry
     *   queue_entries                                              -> queue, reservation
     *   reservations / service_samples                             -> queue
     *   queues                                                     -> business
     *   owners                                                     -> business
     *   businesses
     *
     * The one non-obvious edge: queue_entries carries a FK to reservations
     * (a booking that was checked in becomes a queue entry), so entries must
     * go BEFORE reservations or that constraint fires.
     *
     * Soft delete was considered and rejected. This codebase has no
     * soft-delete convention at all — no deleted_at column, no @SQLDelete, no
     * @Where — so introducing one would mean adding a filter to every query
     * that can reach a business, including discovery and the auth lookups.
     * Missing one would leak a "deleted" restaurant back into public search
     * or let its owner keep signing in, which is a worse failure than the one
     * it would be protecting against. Nothing in the schema requires
     * retention: there is no audit trail, no financial record, no immutable
     * history table. The owner asked for permanent deletion, and permanent
     * deletion is what this does.
     *
     * All of it runs in ONE transaction, so a failure part-way through rolls
     * back rather than leaving a half-deleted tenant.
     */
    @Transactional
    public void deleteBusiness(Long businessId) {
        Business business = businessRepository.findById(businessId)
                .orElseThrow(() -> new NotFoundException("Business not found"));

        // 1. grandchildren of queue_entries
        leaveNowAlertRepository.deleteByBusinessId(businessId);
        pushSubscriptionRepository.deleteByBusinessId(businessId);
        trackingConsentRepository.deleteByBusinessId(businessId);

        // 2. entries — before reservations, see above
        entryRepository.deleteByBusinessId(businessId);

        // 3. the rest of what a queue owns
        reservationRepository.deleteByBusinessId(businessId);
        serviceSampleRepository.deleteByBusinessId(businessId);

        // 4. queues, then the accounts that could reach them
        queueRepository.deleteByBusinessId(businessId);
        ownerRepository.deleteByBusinessId(businessId);

        businessRepository.delete(business);
    }
}
