package com.nowserving.travel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Real traffic-aware travel time from TomTom's Routing API.
 *
 * WHY TOMTOM AND NOT GOOGLE (an ADR you should be able to defend):
 * the PRD names Google's Routes API, but Google requires a billing account
 * with a card before the first request, and the PRD's own risk register
 * flags "Maps API surprise bill — per-element billing, no default cap" as a
 * HIGH risk. TomTom gives live-traffic routing on a free tier with no card,
 * which removes that risk entirely for a learning project. Because everything
 * sits behind {@link TravelTimeProvider}, swapping to Google later is one new
 * class and a config change — which is the entire point of the port.
 *
 * Disabled unless an API key is configured; without one the app quietly uses
 * the free haversine estimate instead.
 */
@Component
public class TomTomTravelTimeProvider implements TravelTimeProvider {

    private static final Logger log = LoggerFactory.getLogger(TomTomTravelTimeProvider.class);

    private final String apiKey;
    private final HttpClient http;
    private final ObjectMapper objectMapper;

    public TomTomTravelTimeProvider(@Value("${app.travel.tomtom.api-key:}") String apiKey,
                                    ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.objectMapper = objectMapper;
        this.http = HttpClient.newBuilder()
                // A routing call that hasn't answered in 3 seconds is useless
                // to us: the scheduler will try again shortly. Timeouts are
                // not optional on outbound calls — an untimed call is a thread
                // you may never get back.
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public Optional<TravelEstimate> travelMinutes(double fromLat, double fromLon,
                                                  double toLat, double toLon) {
        if (!isConfigured()) {
            return Optional.empty(); // caller falls back to haversine
        }
        try {
            // traffic=true asks for live conditions. NOTE: this is the stable
            // Routing API v1. TomTom now recommends Orbis Routing v3 for new
            // integrations; because everything sits behind TravelTimeProvider,
            // migrating is a URL + parse tweak in THIS method only — flip it
            // once a real key can verify the Orbis endpoint end-to-end.
            String url = ("https://api.tomtom.com/routing/1/calculateRoute/"
                    + "%f,%f:%f,%f/json?key=%s&traffic=true&travelMode=car&routeType=fastest")
                    .formatted(fromLat, fromLon, toLat, toLon, apiKey);

            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("TomTom routing returned {}", response.statusCode());
                return Optional.empty();
            }

            var root = objectMapper.readTree(response.body());
            var summary = root.path("routes").path(0).path("summary");
            // travelTimeInSeconds already accounts for live traffic when
            // traffic=true; the delay field is only for reporting.
            int seconds = summary.path("travelTimeInSeconds").asInt(-1);
            if (seconds <= 0) {
                return Optional.empty();
            }
            // How much of the journey is congestion — the number that makes
            // "24 min (8 min of traffic)" feel alive on the ticket screen.
            int delaySeconds = summary.path("trafficDelayInSeconds").asInt(0);
            return Optional.of(new TravelEstimate(
                    (int) Math.ceil(seconds / 60.0), true,
                    (int) Math.round(delaySeconds / 60.0)));

        } catch (Exception e) {
            // Never let a routing hiccup break the queue — same rule as
            // notifications. The caller degrades to the free estimate.
            log.warn("TomTom routing failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** One address/POI match from TomTom Search v2. */
    public record GeocodeResult(String label, double latitude, double longitude) {}

    /**
     * Fuzzy address/POI search (TomTom Search API v2) so owners never type
     * raw coordinates. Empty list when unconfigured or on any failure — the
     * UI falls back to manual lat/lon, same degrade-never-fail rule as
     * routing. Owner-authenticated callers only: this burns free-tier quota
     * (~2.5k/month on legacy Search), so it must not be a public endpoint.
     */
    public java.util.List<GeocodeResult> searchAddress(String query) {
        if (!isConfigured() || query == null || query.isBlank()) {
            return java.util.List.of();
        }
        try {
            String url = "https://api.tomtom.com/search/2/search/%s.json?key=%s&limit=5&typeahead=true"
                    .formatted(java.net.URLEncoder.encode(query.trim(),
                            java.nio.charset.StandardCharsets.UTF_8), apiKey);
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("TomTom search returned {}", response.statusCode());
                return java.util.List.of();
            }
            var results = new java.util.ArrayList<GeocodeResult>();
            for (var r : objectMapper.readTree(response.body()).path("results")) {
                var pos = r.path("position");
                String label = r.path("poi").path("name").asString(null);
                String addr = r.path("address").path("freeformAddress").asString("");
                if (pos.has("lat") && pos.has("lon")) {
                    results.add(new GeocodeResult(
                            label != null && !label.isBlank() ? label + ", " + addr : addr,
                            pos.path("lat").asDouble(), pos.path("lon").asDouble()));
                }
            }
            return results;
        } catch (Exception e) {
            log.warn("TomTom search failed: {}", e.getMessage());
            return java.util.List.of();
        }
    }

    @Override
    public String name() {
        return "tomtom";
    }
}
