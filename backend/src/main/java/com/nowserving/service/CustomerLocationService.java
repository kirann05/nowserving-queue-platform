package com.nowserving.service;

import com.nowserving.entity.QueueEntry;
import com.nowserving.entity.TrackingConsent;
import com.nowserving.exception.NotFoundException;
import com.nowserving.repository.TrackingConsentRepository;
import com.nowserving.repository.QueueEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * FR-11 — opt-in, revocable location sharing, handled as carefully as we can.
 *
 * THREE PRIVACY RULES, straight from PRD §3.8.8, and each is enforced by a
 * design choice rather than a promise in a policy document:
 *
 *  COARSE — we round every coordinate to about 1 km before storing it. That
 *  is plenty to estimate a drive; it is not enough to say which building
 *  someone is in. You cannot leak precision you never kept.
 *
 *  EPHEMERAL — the position lives ONLY in Redis with a TTL, never in
 *  Postgres. It deletes itself, so it can't drift into a backup, a replica,
 *  or an export. Only the CONSENT (no coordinates) is stored durably.
 *
 *  PURPOSE-LIMITED — the only thing this data is used for is computing one
 *  leave-by time. It is never aggregated, never shown to staff, never sold.
 */
@Service
@RequiredArgsConstructor
public class CustomerLocationService {

    /** ~0.01° ≈ 1.1 km. Enough for routing, useless for surveillance. */
    private static final double COARSE_GRID = 0.01;

    /**
     * A shared position is only meaningful for a short while — people move.
     * A short TTL also means "forgetting" is the default rather than a
     * scheduled cleanup job somebody must remember to write.
     */
    private static final Duration LOCATION_TTL = Duration.ofMinutes(30);

    private final QueueEntryRepository entryRepository;
    private final TrackingConsentRepository consentRepository;
    private final StringRedisTemplate redis;
    private final Clock clock;

    public record CoarseLocation(double latitude, double longitude) {}

    /** FR-11: the customer shares where they are (and consents by doing so). */
    @Transactional
    public void share(String entryToken, double latitude, double longitude) {
        QueueEntry entry = requireEntry(entryToken);

        if (consentRepository.findFirstByEntryIdAndRevokedAtIsNull(entry.getId()).isEmpty()) {
            consentRepository.save(new TrackingConsent(entry, clock.instant()));
        }

        double lat = coarsen(latitude);
        double lon = coarsen(longitude);
        redis.opsForValue().set(locationKey(entry.getId()), lat + "," + lon, LOCATION_TTL);
    }

    /**
     * FR-11: "Sharing is opt-in and revocable."
     *
     * Revoking deletes the position immediately rather than waiting for the
     * TTL — when someone withdraws consent, "it'll expire in a bit" is not an
     * acceptable answer.
     */
    @Transactional
    public void revoke(String entryToken) {
        QueueEntry entry = requireEntry(entryToken);
        redis.delete(locationKey(entry.getId()));
        consentRepository.findFirstByEntryIdAndRevokedAtIsNull(entry.getId())
                .ifPresent(consent -> consent.setRevokedAt(clock.instant()));
    }

    /** Empty when never shared, revoked, or simply expired — all normal. */
    public Optional<CoarseLocation> currentLocation(Long entryId) {
        try {
            String value = redis.opsForValue().get(locationKey(entryId));
            if (value == null) return Optional.empty();
            String[] parts = value.split(",");
            return Optional.of(new CoarseLocation(
                    Double.parseDouble(parts[0]), Double.parseDouble(parts[1])));
        } catch (Exception e) {
            return Optional.empty(); // no location simply means no leave-by alert
        }
    }

    private QueueEntry requireEntry(String entryToken) {
        return entryRepository.findByEntryToken(entryToken)
                .orElseThrow(() -> new NotFoundException("Ticket not found"));
    }

    private double coarsen(double value) {
        return Math.round(value / COARSE_GRID) * COARSE_GRID;
    }

    private String locationKey(Long entryId) {
        return "loc:" + entryId;
    }
}
