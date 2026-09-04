package com.nowserving.dto;

import com.nowserving.entity.QueueStatus;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** Owner/staff-facing shapes for /queues/** (NS-4, NS-7, NS-8). */
public final class QueueDtos {

    private QueueDtos() {}

    public record CreateQueueRequest(
            @NotBlank String name,
            // Boxed Integer (not int) so "field omitted" is distinguishable
            // from "0" — null means "use the default from the schema".
            @Min(1) Integer stationCount,
            @Min(1) Integer defaultServiceMinutes) {}

    public record QueueResponse(
            Long id,
            String name,
            QueueStatus status,
            int stationCount,
            int defaultServiceMinutes,
            String joinToken,
            /** The full shareable link — what the owner prints as a QR code. */
            String joinUrl,
            /** How many people are waiting right now. */
            long waitingCount,
            /**
             * WHO is waiting — the first few names, in queue order.
             *
             * A count alone is not enough on a dashboard listing several
             * queues: an owner looking for one customer sees "2 waiting"
             * against three different lines and has to open each in turn.
             * That is exactly how a customer appears to "disappear" when
             * they are simply standing in a queue the owner didn't open.
             */
            java.util.List<String> waitingNames,
            /** Whether this line appears in public discovery. The dashboard
             *  uses it with `status` to tell a line that's shut for the night
             *  (CLOSED but still listed) from one that's been retired
             *  (CLOSED and unlisted). */
            boolean listedPublicly,
            Instant createdAt) {}

    /** One row of the staff dashboard (NS-7). */
    public record WaitingEntryResponse(
            Long entryId,
            String customerName,
            int partySize,
            int position,
            Instant joinedAt,
            /** How long they've been standing (virtually) in line. */
            long waitedMinutes,
            /** FR-15: the customer said they're travelling here. */
            boolean enRoute,
            /** FR-16: their spot is being held until this moment. */
            Instant graceExpiresAt) {}

    /** P0: open/close the queue explicitly — never by editing the database. */
    public record QueueStatusRequest(
            @NotNull QueueStatus status,
            @Size(max = 255) String reason) {}

    /** History rows (SERVED / NO_SHOW / LEFT) — position is meaningless here. */
    public record HistoryEntryResponse(
            Long entryId,
            String customerName,
            int partySize,
            com.nowserving.entity.EntryStatus status,
            Instant joinedAt,
            Instant servedAt,
            /** join -> served, the number the whole product exists to shrink. */
            Long waitedMinutes,
            Integer ratingStars,
            String feedbackComment) {}

    /** The dashboard summary that makes this an operational tool, not a demo. */
    public record QueueStatsResponse(
            QueueStatus status,
            long waitingCount,
            long servedToday,
            /** Measured, not estimated — null until someone has been served today. */
            Long avgWaitMinutesToday,
            /** The operationally honest number (one outlier can't drag it). */
            Long medianWaitMinutesToday,
            Long longestCurrentWaitMinutes,
            long reservationsToday,
            long noShowsToday,
            /** Median minutes between serves lately — "how fast are we moving?". */
            Long paceMinutesPerCustomer) {}

    /**
     * Sprint 5: where the venue is and what happens if someone is late.
     * V9 adds the discovery + remote-joining policy to the same screen,
     * because they are the same conversation: "where am I, who can find me,
     * and from how far away will I accept them?"
     *
     * Every field is nullable — this is a PATCH-shaped PUT: absent means
     * "leave it alone", so the panel can save one toggle without having to
     * round-trip every other setting and risk clobbering a concurrent edit.
     */
    public record VenueConfigRequest(
            @DecimalMin("-90") @DecimalMax("90") Double venueLatitude,
            @DecimalMin("-180") @DecimalMax("180") Double venueLongitude,
            @Min(0) @Max(120) Integer graceMinutes,
            /** 0 = mark a no-show instead of bumping. */
            @Min(0) @Max(50) Integer bumpPlaces,
            @Min(0) @Max(60) Integer safetyBufferMinutes,

            // ---- V9 ----
            Boolean listedPublicly,
            Boolean allowRemoteJoin,
            /**
             * 50 is the CEILING, not the setting. The @Max here, the CHECK
             * constraint in V9 and Queue.MAX_REMOTE_JOIN_MILES_CEILING must
             * agree; the constant exists so a future change has one obvious
             * place to start.
             */
            @Min(1) @Max(50) Integer maxRemoteJoinMiles,
            Boolean allowQrJoin) {}

    public record VenueConfigResponse(
            Double venueLatitude,
            Double venueLongitude,
            int graceMinutes,
            int bumpPlaces,
            int safetyBufferMinutes,
            boolean listedPublicly,
            boolean allowRemoteJoin,
            int maxRemoteJoinMiles,
            boolean allowQrJoin,
            /** So the panel can render "up to N miles" without hard-coding 50. */
            int maxRemoteJoinMilesCeiling) {}

    /** Result of tapping "Next" (NS-8): who was just served, who is now up front. */
    public record AdvanceResponse(
            ServedEntry served,
            WaitingEntryResponse nextUp) {

        public record ServedEntry(Long entryId, String customerName, int partySize, Instant servedAt) {}
    }
}
