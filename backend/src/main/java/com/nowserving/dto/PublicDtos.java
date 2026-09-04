package com.nowserving.dto;

import com.nowserving.entity.EntryStatus;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** Customer-facing shapes for /public/** (NS-5, NS-6). */
public final class PublicDtos {

    private PublicDtos() {}

    /**
     * V9 grew this record, and the order of the fields is the product order:
     * the two REQUIRED things first (name, party size), everything else
     * optional. The join form must stay a 10-second form — anything below
     * customerName/partySize is either invisible to the customer or asked
     * for AFTER they are already in the line.
     */
    public record JoinRequest(
            @NotBlank @Size(max = 100) String customerName,
            @Min(1) Integer partySize,

            /**
             * Where they are, sent ONLY when the restaurant enforces a remote
             * distance limit and only for this one eligibility check. It is
             * never stored — compare, decide, discard. Ongoing Leave-Now
             * sharing is a separate, later, revocable opt-in.
             */
            @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @DecimalMin("-180") @DecimalMax("180") Double longitude,

            /**
             * True ONLY when the customer came through the discovery page —
             * i.e. they are joining from somewhere else and the distance
             * check applies.
             *
             * THE DEFAULT MATTERS, and it is deliberately "not remote".
             *
             * The first version of this field was its mirror image, `viaQr`,
             * defaulting to remote. That broke something real and invisible:
             * every QR code already printed and stuck to a counter points at
             * a bare /j/{token} with no flag on it, so the moment a venue had
             * coordinates configured, every one of those posters started
             * demanding a location the customer had no reason to expect.
             * Eight Leave-Now tests caught it by failing with 428.
             *
             * Defaulting to "physically here" preserves every existing
             * poster and every direct link, and makes the new gate apply
             * only to the new path — which is the one that introduced the
             * problem it solves.
             *
             * Honest limitation: a determined person can simply omit this
             * flag. ACCEPTED — the threat model is "stop a well-meaning
             * customer 45 miles away from holding a table", not "defeat an
             * adversary", and the cost of being wrong is one no-show, which
             * the FR-16 grace/bump policy already handles. Real proof of
             * presence needs venue wifi or a rotating code. Note which way
             * the failure now falls: someone occasionally slips through,
             * rather than every printed poster breaking.
             */
            Boolean remote,

            /** Only collected when notifyChannel is SMS. */
            @Size(max = 20) String phoneNumber,

            /** PUSH (default) | SMS | NONE. */
            com.nowserving.entity.NotifyChannel notifyChannel) {

        /** The pre-V9 shape, for callers that only care about the line. */
        public JoinRequest(String customerName, Integer partySize) {
            this(customerName, partySize, null, null, null, null, null);
        }

        /** Absent flag means "standing here" — see the note above on why
         *  that direction is the safe default. */
        public boolean isRemoteJoin() {
            return Boolean.TRUE.equals(remote);
        }
    }

    /**
     * What a customer gets the moment they join. The entryToken is the ONLY
     * copy they will ever receive — it's their ticket (the future UI stores it
     * in localStorage, NS-10).
     */
    public record JoinResponse(
            String entryToken,
            String queueName,
            int position,
            int peopleAhead,
            int estimatedMinutes) {}

    /**
     * NS-6: the position check. The Integer fields are null once the customer
     * is no longer WAITING — "position" has no meaning for a SERVED ticket.
     */
    public record PositionResponse(
            EntryStatus status,
            Integer position,
            Integer peopleAhead,
            Integer estimatedMinutes,
            /** Set once SERVED — closes the loop ("served at 2:42, waited 18 min")
             *  instead of the story just stopping at "You're up!". */
            Instant servedAt,
            Long waitedMinutes,
            /**
             * V9: WHERE this ticket is for. The ticket page is now something
             * a customer opens hours later, from a notification, having
             * browsed several restaurants — so "you're #6" without a name on
             * it is a genuinely ambiguous message. It was fine when the only
             * way here was scanning a code while standing in the doorway.
             */
            String businessName) {

        /** The pre-P1 shape, for callers that only know the live-line fields. */
        public PositionResponse(EntryStatus status, Integer position,
                                Integer peopleAhead, Integer estimatedMinutes) {
            this(status, position, peopleAhead, estimatedMinutes, null, null, null);
        }
    }

    /**
     * V9: change how you're reached, AFTER joining.
     *
     * This endpoint exists because of a sequencing decision: asking "how
     * should we contact you?" before someone is in the line is a question
     * they have no reason to care about yet. Once they hold ticket #6, the
     * same question is obviously worth answering — so the join stores a
     * default and this lets the ticket page revise it.
     */
    public record NotifyPreferenceRequest(
            @NotNull com.nowserving.entity.NotifyChannel channel,
            @Size(max = 20) String phoneNumber) {}

    /** Post-service feedback: 1–5 stars, optional comment (V8). */
    public record FeedbackRequest(
            @NotNull @Min(1) @jakarta.validation.constraints.Max(5) Integer stars,
            @Size(max = 1000) String comment) {}

    /**
     * FR-8. Exactly the shape the browser's PushSubscription.toJSON() emits,
     * so the frontend can forward it untouched — fewer hand-copied fields,
     * fewer typos.
     */
    public record PushSubscriptionRequest(
            @NotBlank String endpoint,
            @NotNull Keys keys) {

        public record Keys(@NotBlank String p256dh, @NotBlank String auth) {}
    }

    /** So the browser and the server can never disagree about the VAPID key. */
    public record VapidKeyResponse(String publicKey) {}

    /**
     * What this deployment can actually deliver, so the ticket page can offer
     * only the channels that work.
     *
     * Both flags are configuration facts, not user state: `smsAvailable` says
     * Twilio credentials are present, not that this particular customer gave
     * us a number. Without it the UI offered "Text me instead" on a server
     * with no SMS at all — a button guaranteed to fail after the tap.
     */
    public record NotificationCapabilities(boolean pushAvailable, boolean smsAvailable) {}

    // ---- V9: public discovery ----

    /**
     * One restaurant card on the search/nearby page.
     *
     * Note what this deliberately does NOT contain: no queue id, no owner,
     * no customer names, no entry tokens. The only identifier is the
     * joinToken — which is exactly the identifier already printed on the
     * poster, so publishing it here reveals nothing new. That is the whole
     * reason the QR code and the "Join Waitlist" button can share one token:
     * they are the same door, not two systems.
     */
    public record VenueSummary(
            String businessName,
            String queueName,
            /** The one identifier — same token the QR encodes. */
            String joinToken,
            boolean open,
            long partiesWaiting,
            /** Honest range, not a fake-precise single number. */
            int estimatedWaitMinutes,
            int estimatedWaitMaxMinutes,
            /** Null when the customer shared no location, or the venue has none. */
            Double distanceMiles,
            boolean remoteJoinAllowed,
            int maxRemoteJoinMiles,
            boolean qrJoinAllowed,
            /** Null until the owner sets the venue location. */
            Double venueLatitude,
            Double venueLongitude,
            /** True when this customer is too far away to join remotely —
             *  precomputed so the card can say so before they try. */
            boolean tooFarToJoinRemotely,
            /** Lets a card offer "Reserve" without a second request per
             *  restaurant to find out whether it's even possible. */
            boolean reservationsEnabled) {}

    /** The venue page. Same shape as the card plus booking availability, so
     *  the frontend needs no second call to decide which buttons to show. */
    public record VenueDetail(
            VenueSummary venue,
            boolean reservationsEnabled) {}

    /**
     * What a scanned RESTAURANT QR resolves to.
     *
     * A business can run several queues, so this returns all of the ones a
     * customer could act on rather than guessing. The client sends them
     * straight through to the venue page when there is exactly one, which is
     * the overwhelmingly common case; more than one means the customer picks
     * the line they want, which is a question only they can answer.
     *
     * An empty list is a valid, non-error answer: the restaurant exists, it
     * just isn't running a line right now. Its QR must not 404 for that —
     * the poster on the door outlives any individual queue.
     */
    public record RestaurantDetail(
            String businessName,
            String publicToken,
            java.util.List<VenueSummary> queues) {}

    /**
     * One row of the "nearby restaurants" list — a NowServing tenant OR an
     * OpenStreetMap point of interest that has never heard of us, told apart
     * by {@code onNowServing}. Deliberately ONE shape for both rather than
     * two DTOs: the frontend renders one list, and the boolean is exactly
     * the fact it needs to decide "Join Waitlist" vs. "Not on NowServing yet".
     *
     * Everything after {@code address} is null for an external place. A
     * NearbyPlace from OSM must NEVER be given a joinToken — there is no
     * queue behind it, and inventing one would let a customer "join" a line
     * that doesn't exist.
     */
    public record NearbyPlaceResponse(
            String name,
            Double distanceMiles,
            String cuisine,
            String address,
            boolean onNowServing,
            // ---- NowServing-only; null for an external OSM place ----
            String joinToken,
            Boolean joinableNow,
            Boolean remoteJoinEnabled,
            Integer currentWaitMinutes,
            Integer partiesWaiting,
            /**
             * External-place-only: "OPEN_NOW" | "CLOSED" | "UNKNOWN", derived
             * from OSM's opening_hours tag at response-build time (never
             * baked into the cache, so it can't go stale across the 20-minute
             * cache window). Null for a NowServing venue — that side already
             * has an authoritative, staff-controlled signal in {@code
             * joinableNow}, which is a stronger fact than a guess from a
             * public map tag ever could be.
             */
            String openingStatus,
            /** NowServing-only; null for an external place. Lets the card
             *  offer "Reserve" without a second per-restaurant lookup. */
            Boolean reservationsEnabled,
            /**
             * Where the place actually is — populated for BOTH sources, so
             * every card can offer Directions from real coordinates instead
             * of a name guess. Null only when a NowServing owner has not set
             * a venue location yet.
             */
            Double latitude,
            Double longitude) {}

    // ---- Sprint 5: Leave-Now (FR-11..FR-16) ----

    /** FR-11: the browser's Geolocation reading, shared voluntarily. */
    public record ShareLocationRequest(
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude) {}

    /**
     * What the ticket page shows about the journey. All-null when the
     * customer hasn't shared a location — FR-17: location is an enhancement,
     * never a requirement.
     */
    public record LeaveNowResponse(
            boolean sharingLocation,
            Integer travelMinutes,
            boolean trafficAware,
            /** Minutes of the journey that are congestion — 0/null when unknown. */
            Integer trafficDelayMinutes,
            Instant leaveBy,
            boolean shouldLeaveNow,
            boolean enRoute,
            Instant graceExpiresAt,
            /** Shown to every waiting customer, location or not. */
            Instant expectedTurnAt,
            int turnWindowMinutes,
            /** The venue, for the "Open directions" link. Public by nature —
             *  it's the address on the shop's poster. Null until configured. */
            Double venueLatitude,
            Double venueLongitude) {}
}
