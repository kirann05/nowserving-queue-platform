package com.nowserving;

import com.nowserving.entity.EntryStatus;
import com.nowserving.entity.LeaveNowAlert;
import com.nowserving.repository.LeaveNowAlertRepository;
import com.nowserving.repository.TrackingConsentRepository;
import com.nowserving.repository.QueueEntryRepository;
import com.nowserving.service.GracePeriodService;
import com.nowserving.service.LeaveNowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Sprint 5 / FR-11 … FR-17 — the Leave-Now engine. */
class LeaveNowIntegrationTest extends AbstractIntegrationTest {

    @Autowired private LeaveNowService leaveNowService;
    @Autowired private GracePeriodService gracePeriodService;
    @Autowired private QueueEntryRepository entryRepository;
    @Autowired private LeaveNowAlertRepository alertRepository;
    @Autowired private TrackingConsentRepository consentRepository;
    @Autowired private StringRedisTemplate redis;

    // Venue: a point in central London. Origin below is ~8 km away, far
    // enough to clear the "too close to bother routing" pre-filter.
    private static final double VENUE_LAT = 51.5074, VENUE_LON = -0.1278;
    private static final double FAR_LAT = 51.5800, FAR_LON = -0.1000;

    @BeforeEach
    void resetNotifications() {
        fakeNotifications.reset();
    }

    private record Setup(String ownerToken, String joinToken, long queueId) {}

    private Setup venueQueue(String shopName, int gracePlaces) throws Exception {
        String owner = signupAndLogin(uniqueEmail(), shopName);
        var queue = createQueue(owner, "Travel Line");
        long queueId = queue.get("id").asLong();
        mockMvc.perform(put("/queues/{id}/venue-config", queueId)
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"venueLatitude":%f,"venueLongitude":%f,"graceMinutes":0,
                                 "bumpPlaces":%d,"safetyBufferMinutes":5}
                                """.formatted(VENUE_LAT, VENUE_LON, gracePlaces)))
                .andExpect(status().isOk());
        return new Setup(owner, queue.get("joinToken").asString(), queueId);
    }

    private void shareLocation(String entryToken, double lat, double lon) throws Exception {
        mockMvc.perform(post("/public/entries/{t}/location", entryToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":%f,\"longitude\":%f}".formatted(lat, lon)))
                .andExpect(status().isNoContent());
    }

    @Test
    void withoutSharingLocation_thereIsNoJourneyInformation() throws Exception {
        // FR-17: location is an enhancement. Everything else must still work.
        var setup = venueQueue("No Location Shop", 2);
        String ticket = joinQueue(setup.joinToken(), "Private Person").get("entryToken").asString();

        mockMvc.perform(get("/public/entries/{t}/leave-now", ticket))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sharingLocation").value(false))
                .andExpect(jsonPath("$.travelMinutes").doesNotExist());

        // ...and their ordinary position endpoint is unaffected.
        mockMvc.perform(get("/public/entries/{t}", ticket))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.position").value(1));
    }

    @Test
    void sharingLocation_recordsConsent_andStoresOnlyACoarsePositionInRedis() throws Exception {
        var setup = venueQueue("Consent Shop", 2);
        String ticket = joinQueue(setup.joinToken(), "Sharer").get("entryToken").asString();
        long entryId = entryRepository.findByEntryToken(ticket).orElseThrow().getId();

        shareLocation(ticket, 51.58371, -0.10192);

        // The consent record exists — and carries NO coordinates.
        assertThat(consentRepository.findFirstByEntryIdAndRevokedAtIsNull(entryId)).isPresent();

        // The position lives only in Redis, rounded to ~1km, with a TTL so it
        // forgets itself. Precision we never kept cannot leak.
        String stored = redis.opsForValue().get("loc:" + entryId);
        assertThat(stored).isNotNull();
        double storedLat = Double.parseDouble(stored.split(",")[0]);
        assertThat(storedLat)
                .as("coarsened, not the exact reading")
                .isNotEqualTo(51.58371)
                .isCloseTo(51.58, org.assertj.core.data.Offset.offset(0.01));
        assertThat(redis.getExpire("loc:" + entryId)).isPositive();
    }

    @Test
    void revokingConsent_deletesThePositionImmediately() throws Exception {
        var setup = venueQueue("Revoke Shop", 2);
        String ticket = joinQueue(setup.joinToken(), "Changed Mind").get("entryToken").asString();
        long entryId = entryRepository.findByEntryToken(ticket).orElseThrow().getId();

        shareLocation(ticket, FAR_LAT, FAR_LON);
        assertThat(redis.opsForValue().get("loc:" + entryId)).isNotNull();

        mockMvc.perform(delete("/public/entries/{t}/location", ticket))
                .andExpect(status().isNoContent());

        // Gone now — not "gone when the TTL expires".
        assertThat(redis.opsForValue().get("loc:" + entryId)).isNull();
        assertThat(consentRepository.findFirstByEntryIdAndRevokedAtIsNull(entryId)).isEmpty();
        mockMvc.perform(get("/public/entries/{t}/leave-now", ticket))
                .andExpect(jsonPath("$.sharingLocation").value(false));
    }

    @Test
    void withALocationShared_weComputeATravelTimeAndALeaveByMoment() throws Exception {
        var setup = venueQueue("Journey Shop", 2);
        String ticket = joinQueue(setup.joinToken(), "Traveller").get("entryToken").asString();
        shareLocation(ticket, FAR_LAT, FAR_LON);

        mockMvc.perform(get("/public/entries/{t}/leave-now", ticket))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sharingLocation").value(true))
                .andExpect(jsonPath("$.travelMinutes").isNumber())
                .andExpect(jsonPath("$.leaveBy").exists());
    }

    @Test
    void theLeaveNowAlertIsSentOnce_andNeverRepeated() throws Exception {
        // First in line, so their target serve time is essentially "now" —
        // which means the leave-by moment has already passed and the alert
        // is due immediately.
        var setup = venueQueue("Alert Once Shop", 2);
        String ticket = joinQueue(setup.joinToken(), "Alerted").get("entryToken").asString();
        long entryId = entryRepository.findByEntryToken(ticket).orElseThrow().getId();
        shareLocation(ticket, FAR_LAT, FAR_LON);

        // Subscribe so there is somewhere to deliver.
        mockMvc.perform(post("/public/entries/{t}/push-subscription", ticket)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"endpoint":"https://push.test/leave-now","keys":{"p256dh":"k","auth":"a"}}"""));

        leaveNowService.evaluateAndAlert(entryId);
        leaveNowService.evaluateAndAlert(entryId); // the scheduler runs every minute
        leaveNowService.evaluateAndAlert(entryId);

        List<LeaveNowAlert> alerts = alertRepository.findByEntryIdOrderBySentAtDesc(entryId);
        long leaveNowAlerts = alerts.stream()
                .filter(a -> a.getAlertType() == LeaveNowAlert.AlertType.LEAVE_NOW).count();
        assertThat(leaveNowAlerts)
                .as("a scheduler running every minute must not buzz every minute")
                .isEqualTo(1);
    }

    @Test
    void onMyWay_isVisibleToStaff_butTheirLocationNeverIs() throws Exception {
        var setup = venueQueue("En Route Shop", 2);
        String ticket = joinQueue(setup.joinToken(), "Driving Over").get("entryToken").asString();
        shareLocation(ticket, FAR_LAT, FAR_LON);

        mockMvc.perform(post("/public/entries/{t}/on-my-way", ticket))
                .andExpect(status().isNoContent());

        String staffView = mockMvc.perform(get("/queues/{id}/entries", setup.queueId())
                        .header("Authorization", "Bearer " + setup.ownerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].enRoute").value(true))
                .andReturn().getResponse().getContentAsString();

        // Staff learn that someone is coming. They do NOT learn where from.
        assertThat(staffView).doesNotContain("latitude").doesNotContain("longitude");
    }

    @Test
    void holdingASpot_thenLettingItExpire_bumpsThemBackRatherThanDroppingThem() throws Exception {
        // graceMinutes is configured to 0 so the hold expires immediately —
        // the alternative would be a test that sleeps for five real minutes.
        var setup = venueQueue("Grace Shop", 2);
        String late = joinQueue(setup.joinToken(), "Running Late").get("entryToken").asString();
        joinQueue(setup.joinToken(), "Second");
        joinQueue(setup.joinToken(), "Third");
        joinQueue(setup.joinToken(), "Fourth");

        gracePeriodService.requestHold(late);
        assertThat(entryRepository.findByEntryToken(late).orElseThrow().getEnRouteAt())
                .as("asking for a hold implies you're travelling").isNotNull();

        gracePeriodService.applyExpiredGracePeriods();

        // Still in line — moved back 2 places, not thrown out.
        var after = objectMapper.readTree(mockMvc.perform(get("/public/entries/{t}", late))
                .andReturn().getResponse().getContentAsString());
        assertThat(after.get("status").asString()).isEqualTo("WAITING");
        assertThat(after.get("position").asInt()).isEqualTo(3);
    }

    @Test
    void whenThePolicyIsZeroBumpPlaces_anExpiredHoldIsANoShow() throws Exception {
        var setup = venueQueue("Strict Shop", 0); // bumpPlaces = 0
        String late = joinQueue(setup.joinToken(), "Too Late").get("entryToken").asString();
        joinQueue(setup.joinToken(), "Next Person");

        gracePeriodService.requestHold(late);
        gracePeriodService.applyExpiredGracePeriods();

        assertThat(entryRepository.findByEntryToken(late).orElseThrow().getStatus())
                .isEqualTo(EntryStatus.NO_SHOW);
    }

    @Test
    void aQueueWithNoVenueLocation_simplyHasNoJourneyFeature() throws Exception {
        // Owners who never set coordinates lose nothing else.
        String owner = signupAndLogin(uniqueEmail(), "No Coords Shop");
        String joinToken = createQueue(owner, "Plain Queue").get("joinToken").asString();
        String ticket = joinQueue(joinToken, "Someone").get("entryToken").asString();

        shareLocation(ticket, FAR_LAT, FAR_LON); // allowed, just unused

        mockMvc.perform(get("/public/entries/{t}/leave-now", ticket))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sharingLocation").value(false));
    }
}
