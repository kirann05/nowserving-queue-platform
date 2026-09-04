package com.nowserving;

import com.nowserving.entity.ReservationStatus;
import com.nowserving.repository.ReservationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Sprint 4 / FR-10 — reservations. */
class ReservationIntegrationTest extends AbstractIntegrationTest {

    @Autowired private ReservationRepository reservationRepository;

    /** Tomorrow, so slots are always in the future regardless of run time. */
    private LocalDate tomorrow() {
        return LocalDate.now(ZoneId.of("UTC")).plusDays(1);
    }

    private record Booking(String token, String joinToken, long queueId) {}

    /** A queue that takes bookings: 09:00–17:00 UTC, 30-min slots, capacity N. */
    private Booking bookableQueue(String ownerToken, int capacity) throws Exception {
        var queue = createQueue(ownerToken, "Bookable");
        long queueId = queue.get("id").asLong();
        mockMvc.perform(put("/queues/{id}/booking-config", queueId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reservationsEnabled":true,"openingTime":"09:00","closingTime":"17:00",
                                 "slotMinutes":30,"slotCapacity":%d,"timeZone":"UTC"}
                                """.formatted(capacity)))
                .andExpect(status().isOk());
        return new Booking(ownerToken, queue.get("joinToken").asString(), queueId);
    }

    private JsonNode availability(String joinToken, LocalDate date) throws Exception {
        return objectMapper.readTree(mockMvc.perform(
                        get("/public/queues/{t}/availability", joinToken).param("date", date.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private Instant firstFreeSlot(String joinToken) throws Exception {
        var slots = availability(joinToken, tomorrow()).get("slots");
        for (JsonNode slot : slots) {
            if (slot.get("available").asBoolean()) return Instant.parse(slot.get("startTime").asString());
        }
        throw new IllegalStateException("no free slot");
    }

    @Test
    void openingHoursBecomeBookableSlots() throws Exception {
        var q = bookableQueue(signupAndLogin(uniqueEmail(), "Slots Shop"), 1);
        var body = availability(q.joinToken(), tomorrow());
        // 09:00 to 17:00 in 30-minute steps = 16 slots.
        assertThat(body.get("slots").size()).isEqualTo(16);
        assertThat(body.get("slots").get(0).get("capacity").asInt()).isEqualTo(1);
        assertThat(body.get("slots").get(0).get("available").asBoolean()).isTrue();
    }

    @Test
    void bookingASlot_thenSeeingItDisappearFromAvailability() throws Exception {
        var q = bookableQueue(signupAndLogin(uniqueEmail(), "Booking Shop"), 1);
        Instant slot = firstFreeSlot(q.joinToken());

        var response = objectMapper.readTree(mockMvc.perform(
                        post("/public/queues/{t}/reservations", q.joinToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"customerName":"Booker","partySize":2,"slotStart":"%s"}
                                        """.formatted(slot)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        assertThat(response.get("reservationToken").asString()).isNotBlank();
        assertThat(response.get("status").asString()).isEqualTo("BOOKED");

        // Capacity 1, so that slot is now gone.
        var slots = availability(q.joinToken(), tomorrow()).get("slots");
        var thatSlot = java.util.stream.StreamSupport.stream(slots.spliterator(), false)
                .filter(s -> Instant.parse(s.get("startTime").asString()).equals(slot))
                .findFirst().orElseThrow();
        assertThat(thatSlot.get("available").asBoolean()).isFalse();
        assertThat(thatSlot.get("booked").asInt()).isEqualTo(1);
    }

    @Test
    void theSameIdempotencyKeyNeverCreatesTwoBookings() throws Exception {
        var q = bookableQueue(signupAndLogin(uniqueEmail(), "Idempotent Booking"), 5);
        Instant slot = firstFreeSlot(q.joinToken());
        String body = """
                {"customerName":"Double Tapper","partySize":1,"slotStart":"%s"}""".formatted(slot);

        // The customer taps "Book", the response is lost, they tap again.
        var first = objectMapper.readTree(mockMvc.perform(
                        post("/public/queues/{t}/reservations", q.joinToken())
                                .header("Idempotency-Key", "tap-abc-123")
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        var second = objectMapper.readTree(mockMvc.perform(
                        post("/public/queues/{t}/reservations", q.joinToken())
                                .header("Idempotency-Key", "tap-abc-123")
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        // Same reservation returned — not a second seat consumed.
        assertThat(second.get("reservationToken").asString())
                .isEqualTo(first.get("reservationToken").asString());
        assertThat(reservationRepository.findByQueueIdAndSlotStartAndStatusNot(
                q.queueId(), slot, ReservationStatus.CANCELLED)).hasSize(1);
    }

    @Test
    void concurrentBookings_neverExceedCapacity() throws Exception {
        // The NS-8 lesson, re-applied: many customers, one last seat.
        int capacity = 3;
        int simultaneousCustomers = 8;
        var q = bookableQueue(signupAndLogin(uniqueEmail(), "Race Booking"), capacity);
        Instant slot = firstFreeSlot(q.joinToken());

        CountDownLatch startGun = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(simultaneousCustomers);
        try {
            List<Future<Integer>> results = new java.util.ArrayList<>();
            for (int i = 0; i < simultaneousCustomers; i++) {
                final int n = i;
                results.add(pool.submit(() -> {
                    startGun.await();
                    return mockMvc.perform(post("/public/queues/{t}/reservations", q.joinToken())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("""
                                            {"customerName":"Racer %d","slotStart":"%s"}
                                            """.formatted(n, slot)))
                            .andReturn().getResponse().getStatus();
                }));
            }
            startGun.countDown(); // everyone books at once

            long created = results.stream().map(f -> {
                try { return f.get(); } catch (Exception e) { throw new AssertionError(e); }
            }).filter(s -> s == 201).count();

            // Exactly `capacity` succeed; the rest get a clean 409, never a 500.
            assertThat(created).isEqualTo(capacity);

            var booked = reservationRepository.findByQueueIdAndSlotStartAndStatusNot(
                    q.queueId(), slot, ReservationStatus.CANCELLED);
            assertThat(booked).hasSize(capacity);
            // And every winner holds a DIFFERENT seat — the UNIQUE constraint
            // is what makes overbooking physically impossible.
            Set<Integer> seats = booked.stream().map(r -> r.getSeatNo()).collect(Collectors.toSet());
            assertThat(seats).hasSize(capacity);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void checkingIn_placesTheBookingIntoTheLiveLine_keepingItsBookedTime() throws Exception {
        var q = bookableQueue(signupAndLogin(uniqueEmail(), "CheckIn Shop"), 2);
        Instant slot = firstFreeSlot(q.joinToken());

        var reservation = objectMapper.readTree(mockMvc.perform(
                        post("/public/queues/{t}/reservations", q.joinToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"customerName":"Arriving","slotStart":"%s"}""".formatted(slot)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String token = reservation.get("reservationToken").asString();

        // "I'm here" -> becomes an ordinary queue entry with a position.
        var entry = objectMapper.readTree(mockMvc.perform(
                        post("/public/reservations/{t}/check-in", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(entry.get("entryToken").asString()).isNotBlank();
        assertThat(entry.get("position").asInt()).isEqualTo(1);

        // Redeemed once, and only once.
        mockMvc.perform(post("/public/reservations/{t}/check-in", token))
                .andExpect(status().isBadRequest());
        assertThat(reservationRepository.findByReservationToken(token).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.REDEEMED);
    }

    @Test
    void cancellingFreesTheSeat() throws Exception {
        var q = bookableQueue(signupAndLogin(uniqueEmail(), "Cancel Shop"), 1);
        Instant slot = firstFreeSlot(q.joinToken());

        var res = objectMapper.readTree(mockMvc.perform(
                        post("/public/queues/{t}/reservations", q.joinToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"customerName":"Changed Mind","slotStart":"%s"}""".formatted(slot)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        mockMvc.perform(delete("/public/reservations/{t}", res.get("reservationToken").asString()))
                .andExpect(status().isNoContent());

        // Someone else can now take it.
        mockMvc.perform(post("/public/queues/{t}/reservations", q.joinToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerName":"Lucky","slotStart":"%s"}""".formatted(slot)))
                .andExpect(status().isCreated());
    }

    @Test
    void aTimeOutsideOpeningHoursIsRejected() throws Exception {
        var q = bookableQueue(signupAndLogin(uniqueEmail(), "Closed Hours Shop"), 1);
        // 03:17 tomorrow — not a generated slot, and the shop is shut.
        Instant threeAm = tomorrow().atTime(3, 17).atZone(ZoneId.of("UTC")).toInstant();

        mockMvc.perform(post("/public/queues/{t}/reservations", q.joinToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerName":"Night Owl","slotStart":"%s"}""".formatted(threeAm)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("That is not a bookable time slot"));
    }

    @Test
    void aQueueWithoutReservationsEnabled_refusesBookings() throws Exception {
        String owner = signupAndLogin(uniqueEmail(), "Walk-in Only Shop");
        String joinToken = createQueue(owner, "Walk-ins Only").get("joinToken").asString();

        mockMvc.perform(get("/public/queues/{t}/availability", joinToken)
                        .param("date", tomorrow().toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anotherOwnerCannotConfigureMyBookings() throws Exception {
        var q = bookableQueue(signupAndLogin(uniqueEmail(), "Mine"), 1);
        String otherOwner = signupAndLogin(uniqueEmail(), "Theirs");

        // Tenant isolation still holds on the new endpoints: 404, not 403.
        mockMvc.perform(put("/queues/{id}/booking-config", q.queueId())
                        .header("Authorization", "Bearer " + otherOwner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reservationsEnabled\":false}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/queues/{id}/reservations", q.queueId())
                        .header("Authorization", "Bearer " + otherOwner)
                        .param("date", tomorrow().toString()))
                .andExpect(status().isNotFound());
    }
}
