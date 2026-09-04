package com.nowserving.controller;

import com.nowserving.dto.PublicDtos.NearbyPlaceResponse;
import com.nowserving.dto.PublicDtos.VenueDetail;
import com.nowserving.dto.PublicDtos.VenueSummary;
import com.nowserving.service.DiscoveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * V9 — the public restaurant search. Under /public/**, so it inherits both
 * the permitAll rule in SecurityConfig and the RateLimitFilter, which is
 * exactly right for the one endpoint in the app that an anonymous crawler
 * will find first.
 *
 * GET-only and side-effect free: no rows are written, nothing is remembered
 * about who searched. The optional lat/lng are used to compute a distance and
 * then discarded with the request — they never reach the database, and unlike
 * the Leave-Now flow they aren't even written to Redis, because there is no
 * ongoing journey to track yet.
 */
@RestController
@RequiredArgsConstructor
public class DiscoveryController {

    private final DiscoveryService discoveryService;

    /**
     * Search + nearby in one endpoint, because they are the same question
     * asked with different inputs. Two endpoints would mean two code paths
     * that must agree about wait estimates and sorting.
     */
    @GetMapping("/public/venues")
    public List<VenueSummary> search(@RequestParam(name = "q", required = false) String q,
                                     @RequestParam(required = false) Double lat,
                                     @RequestParam(required = false) Double lng) {
        return discoveryService.search(q, lat, lng);
    }

    /**
     * One restaurant. The joinToken in the path is the SAME token the QR code
     * encodes — which is the whole design: /v/{joinToken} (browsed to) and
     * /j/{joinToken} (scanned) are two doors into one queue.
     */
    @GetMapping("/public/venues/{joinToken}")
    public VenueDetail venue(@PathVariable String joinToken,
                             @RequestParam(required = false) Double lat,
                             @RequestParam(required = false) Double lng) {
        return discoveryService.venue(joinToken, lat, lng);
    }

    /**
     * "What's near me?" — NowServing venues merged with OpenStreetMap points
     * of interest. Location is REQUIRED here (unlike {@link #search}), since
     * "nearby" has no meaning without an origin — a bad lat/lon just returns
     * an empty or nonsensical list rather than 500ing, which is why there's
     * no validation beyond what the query params already require.
     *
     * radiusMiles defaults to 3 — close enough to walk or a short drive,
     * without the endpoint quietly returning a whole city.
     */
    @GetMapping("/public/venues/nearby")
    public List<NearbyPlaceResponse> nearby(@RequestParam double lat,
                                            @RequestParam double lon,
                                            @RequestParam(defaultValue = "3") double radiusMiles) {
        return discoveryService.nearby(lat, lon, Math.min(radiusMiles, 25));
    }

    /**
     * A scanned RESTAURANT QR code. Permanent per business, unlike
     * /public/venues/{joinToken}, which dies with its queue.
     */
    @GetMapping("/public/restaurants/{publicToken}")
    public com.nowserving.dto.PublicDtos.RestaurantDetail restaurant(
            @PathVariable String publicToken,
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng) {
        return discoveryService.restaurant(publicToken, lat, lng);
    }
}
