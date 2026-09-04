package com.nowserving.places;

import com.nowserving.travel.HaversineTravelTimeProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Nearby restaurants from OpenStreetMap via the public Overpass API.
 *
 * SAME SHAPE AS TomTomTravelTimeProvider ON PURPOSE: a free/optional external
 * source, a short connect timeout, and "never throw — an empty list means
 * the caller falls back to whatever it already had" (here: NowServing's own
 * listed venues, which discovery always has regardless of this provider).
 *
 * DELIBERATELY NOT CALLED FROM REACT. Overpass is a shared, rate-limited
 * public service with no API key and no SLA — calling it from the browser
 * would mean every visitor's IP hits it directly, with no cache in front and
 * no way to protect it from a traffic spike. Routing it through the backend
 * gives us one place to cache, one place to add a timeout, and one outage
 * that degrades gracefully instead of many browsers each failing loudly.
 *
 * CACHING, in two tiers under two different keys per geo-cell:
 *   "nearby-restaurants:{cell}"       — the normal 20-minute cache. A hit
 *                                       here is served as-is.
 *   "nearby-restaurants-good:{cell}"  — "last known good", written ONLY
 *                                       alongside a fresh-cache write, kept
 *                                       for 24 hours. Read ONLY when a live
 *                                       Overpass call fails and there is no
 *                                       fresh entry.
 *
 * The split fixes a real incident: a single Overpass timeout used to get
 * cached as "zero restaurants here" for the full 20-minute TTL — visually
 * indistinguishable from a genuinely restaurant-free area, and served to
 * every visitor in that cell for the next 20 minutes regardless of whether
 * Overpass had already recovered. Now a failed fetch NEVER writes the fresh
 * cache and NEVER overwrites the good cache — it only reads the good cache
 * as a fallback, so a transient outage degrades to "yesterday's answer",
 * not to a false "nothing's here" that then persists even after recovery.
 *
 * A second, independent bug lived in the read path: the cache used to be
 * WRITTEN as a serialized {@code List<NearbyPlace>} but READ by feeding that
 * JSON through {@link #parse}, which expects the raw Overpass response shape
 * ({@code {"elements": [...]}}). A JSON array has no "elements" key, so
 * `root.path("elements")` silently returned nothing to iterate — every cache
 * HIT produced zero results, meaning a restaurant list that rendered
 * correctly once would vanish on the very next 20-second poll. Fixed by
 * {@link #readCache} deserializing directly to {@code List<NearbyPlace>}
 * rather than round-tripping through the Overpass-shaped parser.
 */
@Component
public class OverpassPlacesProvider implements PlacesProvider {

    private static final Logger log = LoggerFactory.getLogger(OverpassPlacesProvider.class);

    /** Same cell size as the ETA cache in GuardedTravelTimeService — restaurants
     *  a kilometre apart round to the same cache entry, which is fine at this
     *  granularity. */
    private static final double GRID = 0.01;

    private static final Duration FRESH_TTL = Duration.ofMinutes(20);
    /** Long enough to survive a bad afternoon of Overpass flakiness; short
     *  enough that a permanently-closed restaurant eventually ages out. */
    private static final Duration GOOD_TTL = Duration.ofHours(24);

    private static final TypeReference<List<NearbyPlace>> PLACE_LIST_TYPE = new TypeReference<>() {};

    /** 1 mile = 1.609344 km — used only for the human-readable distance field. */
    private static final double KM_PER_MILE = 1.609344;

    private final String overpassUrl;
    private final HttpClient http;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redis;

    /**
     * The URL is injectable (default: the real public instance) purely so a
     * test can point this at a local HTTP server instead — parsing a
     * third-party API's JSON is real product behaviour now, and that needs
     * to be tested without depending on Overpass's actual uptime.
     */
    public OverpassPlacesProvider(
            @Value("${app.places.overpass-url:https://overpass-api.de/api/interpreter}") String overpassUrl,
            ObjectMapper objectMapper, StringRedisTemplate redis) {
        this.overpassUrl = overpassUrl;
        this.objectMapper = objectMapper;
        this.redis = redis;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    @Override
    public List<NearbyPlace> nearbyRestaurants(double lat, double lon, int radiusMeters) {
        String cell = "%s:%s:%d".formatted(cell(lat), cell(lon), radiusMeters);
        String freshKey = "nearby-restaurants:" + cell;
        String goodKey = "nearby-restaurants-good:" + cell;

        List<NearbyPlace> fresh = readCache(freshKey);
        if (fresh != null) {
            return fresh;
        }

        List<NearbyPlace> fetched = fetch(lat, lon, radiusMeters);
        if (fetched == null) {
            // The live call failed. This is the fix's whole point: do NOT
            // write an empty result to the fresh cache — that would make a
            // transient outage look identical to "no restaurants here" for
            // the next 20 minutes, to every visitor in this cell. Fall back
            // to whatever we last confirmed, if anything.
            List<NearbyPlace> stale = readCache(goodKey);
            if (stale != null) {
                log.info("Overpass call failed for cell {}; serving {} last-known-good places",
                        cell, stale.size());
                return stale;
            }
            log.info("Overpass call failed for cell {}; no prior data to fall back to", cell);
            return List.of();
        }

        writeCache(freshKey, fetched, FRESH_TTL);
        writeCache(goodKey, fetched, GOOD_TTL);
        return fetched;
    }

    private List<NearbyPlace> readCache(String key) {
        try {
            String cached = redis.opsForValue().get(key);
            if (cached == null) return null;
            // Read back the SAME shape that was written — a List<NearbyPlace>,
            // not the raw Overpass response. Feeding this JSON through the
            // Overpass-response parser instead (as an earlier version did)
            // silently produced zero results on every cache hit, because
            // `{"elements":[...]}`-shaped parsing finds no "elements" key on
            // a bare JSON array.
            return objectMapper.readValue(cached, PLACE_LIST_TYPE);
        } catch (Exception e) {
            log.debug("Nearby-places cache read failed for key {}, treating as a miss", key, e);
            return null;
        }
    }

    private void writeCache(String key, List<NearbyPlace> places, Duration ttl) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(places), ttl);
        } catch (Exception e) {
            log.debug("Nearby-places cache write failed for key {}", key, e);
        }
    }

    /** @return the parsed places, or {@code null} if the call failed outright
     *  (as opposed to succeeding with zero results) — the caller needs that
     *  distinction to decide whether the cache may be trusted. */
    private List<NearbyPlace> fetch(double lat, double lon, int radiusMeters) {
        String origin = "around:%d,%s,%s".formatted(radiusMeters,
                String.valueOf(lat).replace(',', '.'),
                String.valueOf(lon).replace(',', '.'));
        // Overpass QL: every restaurant tagged amenity=restaurant within
        // radiusMeters, whether mapped as a NODE (a point), a WAY (a
        // building outline — common for large chains, food courts, malls)
        // or a RELATION (a multi-polygon building). `out center` gives
        // ways/relations a computed centroid coordinate instead of their
        // full geometry; nodes are unaffected (they already have lat/lon).
        // Restricting the query to node-only — as an earlier version did —
        // silently dropped every way/relation-mapped restaurant: not
        // miscounted, simply never asked for.
        String query = ("[out:json][timeout:8];(node[\"amenity\"=\"restaurant\"](%s);"
                + "way[\"amenity\"=\"restaurant\"](%s);relation[\"amenity\"=\"restaurant\"](%s);"
                + ");out center 30;")
                .formatted(origin, origin, origin);

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(overpassUrl))
                    .timeout(Duration.ofSeconds(6))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "data=" + java.net.URLEncoder.encode(query, StandardCharsets.UTF_8)))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Overpass returned HTTP {}", response.statusCode());
                return null;
            }
            return parse(objectMapper.readTree(response.body()), lat, lon);
        } catch (Exception e) {
            // Never let a flaky public API break restaurant discovery — the
            // page still has every NowServing venue without it, and the
            // caller falls back to last-known-good data if any exists.
            log.warn("Overpass nearby-restaurants lookup failed: {} ({})",
                    e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    private List<NearbyPlace> parse(JsonNode root, double originLat, double originLon) {
        List<NearbyPlace> places = new ArrayList<>();
        for (JsonNode el : root.path("elements")) {
            JsonNode tags = el.path("tags");
            String name = tags.path("name").asString(null);
            if (name == null || name.isBlank()) {
                continue; // an unnamed node is useless on a restaurant card
            }

            // Nodes carry lat/lon directly; ways/relations only get
            // coordinates because the query asked for `out center`, which
            // puts them under element.center.{lat,lon} instead.
            boolean hasDirectCoords = el.has("lat") && el.has("lon");
            boolean hasCenterCoords = el.path("center").has("lat") && el.path("center").has("lon");
            if (!hasDirectCoords && !hasCenterCoords) {
                continue; // no usable coordinate at all — can't place it on a map
            }
            double lat = hasDirectCoords ? el.path("lat").asDouble() : el.path("center").path("lat").asDouble();
            double lon = hasDirectCoords ? el.path("lon").asDouble() : el.path("center").path("lon").asDouble();
            double km = HaversineTravelTimeProvider.distanceKm(originLat, originLon, lat, lon);

            places.add(new NearbyPlace(
                    "osm:" + el.path("id").asLong(),
                    name,
                    lat, lon,
                    tags.path("cuisine").asString(null),
                    address(tags),
                    Math.round(km / KM_PER_MILE * 10.0) / 10.0,
                    "OSM",
                    tags.path("opening_hours").asString(null)));
        }
        return places;
    }

    /** OSM splits an address across several tags; join what's present. */
    private String address(JsonNode tags) {
        String houseNumber = tags.path("addr:housenumber").asString(null);
        String street = tags.path("addr:street").asString(null);
        if (street == null) return null;
        return houseNumber != null ? houseNumber + " " + street : street;
    }

    /** Rounds to the same ~1.1km grid GuardedTravelTimeService uses for ETAs. */
    private String cell(double coordinate) {
        return String.format(Locale.ROOT, "%.2f", Math.round(coordinate / GRID) * GRID);
    }
}
