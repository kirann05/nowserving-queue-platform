package com.nowserving.service;

import com.nowserving.dto.PublicDtos.NearbyPlaceResponse;
import com.nowserving.dto.PublicDtos.VenueDetail;
import com.nowserving.dto.PublicDtos.VenueSummary;
import com.nowserving.entity.EntryStatus;
import com.nowserving.entity.Queue;
import com.nowserving.entity.QueueStatus;
import com.nowserving.exception.NotFoundException;
import com.nowserving.places.NearbyPlace;
import com.nowserving.places.OpeningHoursEvaluator;
import com.nowserving.places.OpeningStatus;
import com.nowserving.places.PlacesProvider;
import com.nowserving.repository.QueueEntryRepository;
import com.nowserving.repository.QueueRepository;
import com.nowserving.travel.HaversineTravelTimeProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * V9 — the public front door: "which restaurants are there, and how long is
 * the wait right now?"
 *
 * THE ARCHITECTURAL POINT, and the reason this is a small class rather than a
 * new subsystem: discovery introduces no second way to join. It surfaces the
 * SAME joinToken that the QR code has always encoded. One restaurant → one
 * active queue → one token → two doors (scan it, or tap "Join Waitlist").
 * Every downstream thing — position, estimates, Leave-Now, notifications —
 * carries on knowing nothing about which door the customer used.
 *
 * The only place the door matters at all is the remote-distance check in
 * {@link PublicQueueService}, and even there it's a single boolean.
 */
@Service
@RequiredArgsConstructor
public class DiscoveryService {

    /** 1 mile = 1.609344 km. Named because a bare 1.609 in a formula is a
     *  puzzle six months from now. */
    private static final double KM_PER_MILE = 1.609344;

    /**
     * Wait estimates are shown as a RANGE, never a single number. "~25 min"
     * reads as a promise; "25–35 min" reads as an estimate, which is what it
     * actually is. This is the same honesty principle as the ticket page's
     * turn window and the median-not-mean choice in Sprint 3 — the cheapest
     * way to keep a wait-time product trustworthy is to never overstate its
     * own precision.
     */
    private static final double RANGE_UPPER_FACTOR = 1.4;

    /**
     * Two OSM points within this distance of a NowServing venue are treated
     * as the SAME physical restaurant, and the OSM copy is dropped. Without
     * this, a participating restaurant would show up twice — once correctly,
     * once again as "Not on NowServing yet" a few metres away — which reads
     * as a bug the first time anyone notices it.
     */
    private static final double SAME_PLACE_MILES = 0.03; // ~50 metres

    private final QueueRepository queueRepository;
    private final com.nowserving.repository.BusinessRepository businessRepository;
    private final QueueEntryRepository entryRepository;
    private final WaitEstimator waitEstimator;
    private final PlacesProvider placesProvider;

    /**
     * The search / nearby list.
     *
     * @param query   free text over business and queue name; blank = everything
     * @param lat/lng the customer's rough position, if they offered it. Entirely
     *                optional (FR-17's principle): without it the page still
     *                works, it just can't sort by distance or pre-warn about
     *                the remote limit.
     */
    @Transactional(readOnly = true)
    public List<VenueSummary> search(String query, Double lat, Double lng) {
        List<VenueSummary> venues = queueRepository.findListed(query == null ? "" : query.trim())
                .stream()
                .map(queue -> toSummary(queue, lat, lng))
                .toList();

        // Ordering is a product decision, so it is spelled out rather than
        // left to the database: open restaurants first (a closed one is not
        // an answer to "where can I eat now"), then nearest, then shortest
        // wait. Venues with no distance sort after those with one instead of
        // silently winning or losing a null comparison.
        return venues.stream()
                .sorted(Comparator
                        .comparing(VenueSummary::open, Comparator.reverseOrder())
                        .thenComparing(v -> v.distanceMiles() == null
                                ? Double.MAX_VALUE : v.distanceMiles())
                        .thenComparingInt(VenueSummary::estimatedWaitMinutes))
                .toList();
    }

    /**
     * What a scanned RESTAURANT QR resolves to — /public/restaurants/{token}.
     *
     * The permanent, business-level counterpart to venue(). A queue's
     * join_token dies with its queue; this one is printed on a poster and has
     * to outlive every queue behind it, so it resolves the BUSINESS and then
     * looks up whatever lines that business is running right now.
     *
     * Deliberately NOT filtered by listedPublicly, for the same reason
     * venue() isn't: "unlisted" means "don't advertise me in search", not
     * "my own QR code should stop working".
     */
    @Transactional(readOnly = true)
    public com.nowserving.dto.PublicDtos.RestaurantDetail restaurant(
            String publicToken, Double lat, Double lng) {
        com.nowserving.entity.Business business = businessRepository.findByPublicToken(publicToken)
                .orElseThrow(() -> new NotFoundException("Restaurant not found"));

        // Open lines first, then the ones a customer can at least look at.
        List<VenueSummary> queues = queueRepository.findByBusinessIdOrderByCreatedAtAsc(business.getId()).stream()
                .map(queue -> toSummary(queue, lat, lng))
                .sorted(Comparator.comparing(VenueSummary::open, Comparator.reverseOrder())
                        .thenComparingInt(VenueSummary::estimatedWaitMinutes))
                .toList();

        return new com.nowserving.dto.PublicDtos.RestaurantDetail(
                business.getName(), business.getPublicToken(), queues);
    }

    /** The venue page behind a card — or behind a scanned QR code. */
    @Transactional(readOnly = true)
    public VenueDetail venue(String joinToken, Double lat, Double lng) {
        Queue queue = queueRepository.findByJoinToken(joinToken)
                .orElseThrow(() -> new NotFoundException("Restaurant not found"));
        // Deliberately NOT filtered by listedPublicly: an unlisted restaurant
        // is hidden from SEARCH, but its QR code and its direct link must
        // still work. "Unlisted" means "don't advertise me", not "closed".
        return new VenueDetail(toSummary(queue, lat, lng), queue.isReservationsEnabled());
    }

    private VenueSummary toSummary(Queue queue, Double lat, Double lng) {
        long waiting = entryRepository.countByQueueIdAndStatus(queue.getId(), EntryStatus.WAITING);

        // The number a customer joining RIGHT NOW would see — everyone
        // currently waiting is ahead of them. Reusing WaitEstimator (rather
        // than re-deriving the maths here) is what stops the discovery page
        // and the ticket page from quoting two different waits for the same
        // line, which is exactly the bug Sprint 3 fixed once already.
        int low = waitEstimator.estimateMinutes(queue, (int) waiting);
        int high = (int) Math.ceil(low * RANGE_UPPER_FACTOR);

        Double distance = distanceMiles(queue, lat, lng);
        boolean tooFar = queue.isAllowRemoteJoin()
                && distance != null
                && distance > queue.getMaxRemoteJoinMiles();

        return new VenueSummary(
                queue.getBusiness().getName(),
                queue.getName(),
                queue.getJoinToken(),
                queue.getStatus() == QueueStatus.OPEN,
                waiting,
                low,
                high,
                distance,
                queue.isAllowRemoteJoin(),
                queue.getMaxRemoteJoinMiles(),
                queue.isAllowQrJoin(),
                queue.getVenueLatitude(),
                queue.getVenueLongitude(),
                tooFar,
                queue.isReservationsEnabled());
    }

    /**
     * "What restaurants are near me?" — NowServing's own tenants merged with
     * OpenStreetMap points of interest, told apart by {@code onNowServing}.
     *
     * NowServing is the SOURCE OF TRUTH for anything that implies you can
     * join a line: {@code joinToken}, {@code joinableNow}, wait time, parties
     * waiting. An OSM place NEVER gets those — see NearbyPlaceResponse's
     * class comment for why that boundary matters.
     *
     * radiusMiles is a customer-facing unit; it's converted to metres once,
     * here, because that's the one place both sources need it in a different
     * unit (the DB query works in miles via Haversine, Overpass's `around`
     * filter is metres).
     */
    @Transactional(readOnly = true)
    public List<NearbyPlaceResponse> nearby(double lat, double lng, double radiusMiles) {
        List<Queue> listed = queueRepository.findListed("");

        // EVERY published NowServing venue, whatever its distance and
        // whether or not it has coordinates at all.
        //
        // radiusMiles exists to bound the Overpass query — without it we'd
        // pull the whole map. It was also being applied to our own tenants,
        // and two filters here silently removed them from discovery:
        //
        //   hasVenueLocation()        -> a published venue whose owner had
        //                                not set a location yet vanished
        //   distanceMiles <= radius   -> a published venue further than the
        //                                3-mile default vanished
        //
        // So "On NowServing" showed nothing unless the customer happened to
        // be within three miles of a fully-configured venue. Participating
        // restaurants are a handful of rows we own, not a map to bound:
        // listedPublicly is the ONLY thing that decides whether they appear,
        // and open/closed decides only what you can DO with them. Venues
        // without coordinates simply report a null distance and sort last.
        List<VenueSummary> nearbyVenues = listed.stream()
                .map(queue -> toSummary(queue, lat, lng))
                .toList();

        List<NearbyPlace> external = placesProvider.nearbyRestaurants(
                lat, lng, (int) Math.round(radiusMiles * KM_PER_MILE * 1000));

        java.time.ZonedDateTime now = java.time.ZonedDateTime.now();
        List<NearbyPlaceResponse> result = new java.util.ArrayList<>();
        for (VenueSummary v : nearbyVenues) {
            result.add(new NearbyPlaceResponse(
                    v.businessName(), v.distanceMiles(), null, null, true,
                    v.joinToken(), v.open(), v.remoteJoinAllowed(),
                    v.open() ? v.estimatedWaitMinutes() : null,
                    v.open() ? (int) v.partiesWaiting() : null,
                    null, // NowServing already has an authoritative open/closed signal
                    v.reservationsEnabled(),
                    v.venueLatitude(), v.venueLongitude()));
        }

        for (NearbyPlace place : external) {
            // Drop anything that's really the same physical restaurant as a
            // NowServing venue we already listed above.
            boolean isDuplicate = nearbyVenues.stream().anyMatch(v ->
                    v.venueLatitude() != null && v.venueLongitude() != null
                            && milesBetween(v.venueLatitude(), v.venueLongitude(),
                                    place.latitude(), place.longitude()) <= SAME_PLACE_MILES);
            if (isDuplicate) continue;

            // Evaluated NOW, from the raw tag — never baked into the cached
            // NearbyPlace — so "open now" can't outlive an actual closing
            // time just because it was cached 15 minutes ago.
            OpeningStatus status = OpeningHoursEvaluator.evaluate(place.openingHours(), now);

            result.add(new NearbyPlaceResponse(
                    place.name(), place.distanceMiles(), place.cuisine(), place.address(),
                    false, null, null, null, null, null, status.name(), null,
                    place.latitude(), place.longitude()));
        }

        // Ordering, in three tiers:
        //   1. NowServing venues before external ones — they're the only
        //      rows a customer can actually join, so thirty OSM results must
        //      never bury them.
        //   2. Within each group, open before closed. NowServing uses its
        //      authoritative joinableNow; external places fall back to the
        //      OSM tag, preferring OPEN_NOW > UNKNOWN > CLOSED. Neither
        //      group ever DROPS a closed row — an unreadable opening_hours
        //      tag is not evidence a restaurant is less real, and a closed
        //      participating venue is still somewhere you can look at,
        //      navigate to, and come back to tomorrow.
        //   3. Then distance, with unknown distance last.
        return result.stream()
                .sorted(Comparator
                        .comparing(NearbyPlaceResponse::onNowServing, Comparator.reverseOrder())
                        .thenComparingInt(DiscoveryService::openRank)
                        .thenComparing(r -> r.distanceMiles() == null
                                ? Double.MAX_VALUE : r.distanceMiles()))
                .toList();
    }

    /** Open-first, within whichever group the row belongs to. */
    private static int openRank(NearbyPlaceResponse r) {
        if (r.onNowServing()) return Boolean.TRUE.equals(r.joinableNow()) ? 0 : 1;
        return openingRank(r.openingStatus());
    }

    private static int openingRank(String openingStatus) {
        if (openingStatus == null) return 0; // NowServing venues: not ranked by this axis
        return switch (OpeningStatus.valueOf(openingStatus)) {
            case OPEN_NOW -> 0;
            case UNKNOWN -> 1;
            case CLOSED -> 2;
        };
    }

    private double milesBetween(double lat1, double lon1, double lat2, double lon2) {
        return HaversineTravelTimeProvider.distanceKm(lat1, lon1, lat2, lon2) / KM_PER_MILE;
    }

    /** Null whenever either end of the line is unknown — no location shared,
     *  or the owner never set the venue's coordinates. */
    private Double distanceMiles(Queue queue, Double lat, Double lng) {
        if (lat == null || lng == null || !queue.hasVenueLocation()) return null;
        double km = HaversineTravelTimeProvider.distanceKm(
                lat, lng, queue.getVenueLatitude(), queue.getVenueLongitude());
        return Math.round(km / KM_PER_MILE * 10.0) / 10.0; // one decimal is plenty
    }
}
