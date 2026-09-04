package com.nowserving;

import com.nowserving.places.OpeningHoursEvaluator;
import com.nowserving.places.OpeningStatus;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 8's "honest three-valued status" requirement. Plain unit tests — no
 * Spring context, no network, no Redis — since this is pure function logic
 * over a string and a clock.
 */
class OpeningHoursEvaluatorTest {

    // A Wednesday at 10:00.
    private static final ZonedDateTime WEDNESDAY_10AM =
            ZonedDateTime.of(2024, 1, 3, 10, 0, 0, 0, ZoneOffset.UTC);

    @Test
    void missingOpeningHours_isUnknown_neverClosed() {
        assertThat(OpeningHoursEvaluator.evaluate(null, WEDNESDAY_10AM)).isEqualTo(OpeningStatus.UNKNOWN);
        assertThat(OpeningHoursEvaluator.evaluate("", WEDNESDAY_10AM)).isEqualTo(OpeningStatus.UNKNOWN);
        assertThat(OpeningHoursEvaluator.evaluate("   ", WEDNESDAY_10AM)).isEqualTo(OpeningStatus.UNKNOWN);
    }

    @Test
    void twentyFourSeven_isAlwaysOpen() {
        assertThat(OpeningHoursEvaluator.evaluate("24/7", WEDNESDAY_10AM)).isEqualTo(OpeningStatus.OPEN_NOW);
    }

    @Test
    void currentlyWithinASimpleRange_isOpenNow() {
        // Wednesday 10:00 falls inside Mo-Fr 09:00-17:00.
        assertThat(OpeningHoursEvaluator.evaluate("Mo-Fr 09:00-17:00", WEDNESDAY_10AM))
                .isEqualTo(OpeningStatus.OPEN_NOW);
    }

    @Test
    void currentlyOutsideARange_onADayItMentions_isClosed() {
        ZonedDateTime wednesdayEvening = WEDNESDAY_10AM.withHour(20);
        assertThat(OpeningHoursEvaluator.evaluate("Mo-Fr 09:00-17:00", wednesdayEvening))
                .isEqualTo(OpeningStatus.CLOSED);
    }

    @Test
    void aDayNotMentionedAtAll_isClosed_notUnknown() {
        // Sunday isn't in Mo-Fr — a confidently-read tag that simply doesn't
        // open today is a definite CLOSED, not a shrug.
        ZonedDateTime sunday = WEDNESDAY_10AM.plusDays(4); // Wed -> Sunday
        assertThat(OpeningHoursEvaluator.evaluate("Mo-Fr 09:00-17:00", sunday))
                .isEqualTo(OpeningStatus.CLOSED);
    }

    @Test
    void commaSeparatedDaysAndSplitHours_areBothHonoured() {
        // Mo,We,Fr only; two separate windows with a midday gap.
        assertThat(OpeningHoursEvaluator.evaluate("Mo,We,Fr 09:00-12:00,13:00-18:00", WEDNESDAY_10AM))
                .isEqualTo(OpeningStatus.OPEN_NOW);
        ZonedDateTime lunchGap = WEDNESDAY_10AM.withHour(12).withMinute(30);
        assertThat(OpeningHoursEvaluator.evaluate("Mo,We,Fr 09:00-12:00,13:00-18:00", lunchGap))
                .isEqualTo(OpeningStatus.CLOSED);
    }

    @Test
    void overnightRange_isOpenPastMidnightAndBeforeTheCloseTime() {
        // 18:00-02:00: still open at 01:00 the NEXT calendar day.
        ZonedDateTime wedOneAm = WEDNESDAY_10AM.withHour(1);
        assertThat(OpeningHoursEvaluator.evaluate("Tu-Su 18:00-02:00", wedOneAm))
                .as("01:00 Wednesday is still within Tuesday's overnight 18:00-02:00 window")
                .isEqualTo(OpeningStatus.OPEN_NOW);
    }

    @Test
    void laterRuleOverridesEarlierRuleForTheSameDay() {
        // "Sa off" must win over any earlier rule that also touches Saturday.
        ZonedDateTime saturdayNoon = WEDNESDAY_10AM.plusDays(3).withHour(12); // Wed -> Sat
        assertThat(OpeningHoursEvaluator.evaluate("Mo-Su 09:00-18:00; Sa off", saturdayNoon))
                .isEqualTo(OpeningStatus.CLOSED);
    }

    @Test
    void noDayPrefix_appliesToEveryDay() {
        assertThat(OpeningHoursEvaluator.evaluate("09:00-17:00", WEDNESDAY_10AM))
                .isEqualTo(OpeningStatus.OPEN_NOW);
    }

    // ---------- regression: "24:00" used to crash the whole endpoint ----------
    //
    // A real production incident, not a hypothetical: OverpassPlacesProvider
    // returned a real restaurant near a real user's location tagged
    // "Mo-Tu 16:00-24:00; ...". "24:00" is OSM's own idiom for midnight at
    // the end of the day, but java.time's LocalTime rejects hour 24
    // (LocalTime.parse("24:00") throws DateTimeException). That exception was
    // uncaught, so DiscoveryService.nearby() 500'd for EVERY request in any
    // cell containing that one restaurant — which read, from the customer's
    // side, as "no restaurants near me" with no visible error at all.

    @Test
    void endOfDayTwentyFourHundred_neverThrows_andIsReadAsMidnight() {
        // The exact failing tag from the incident, verbatim.
        assertThat(OpeningHoursEvaluator.evaluate(
                "Mo-Tu 16:00-24:00; We,Th,Su 11:00-24:00; Fr-Sa 11:00-02:00", WEDNESDAY_10AM))
                .as("10am is before the 16:00 opening on a Wed->handled-as-We rule; must not throw")
                .isEqualTo(OpeningStatus.CLOSED);

        ZonedDateTime wedEvening = WEDNESDAY_10AM.withHour(20); // still Wednesday = "We"
        assertThat(OpeningHoursEvaluator.evaluate(
                "Mo-Tu 16:00-24:00; We,Th,Su 11:00-24:00; Fr-Sa 11:00-02:00", wedEvening))
                .isEqualTo(OpeningStatus.OPEN_NOW);
    }

    @Test
    void sixteenToTwentyFourHundred_isOpenRightUpToMidnight_andClosedJustAfter() {
        ZonedDateTime justBeforeMidnight = WEDNESDAY_10AM.withHour(23).withMinute(59);
        assertThat(OpeningHoursEvaluator.evaluate("Mo-Su 16:00-24:00", justBeforeMidnight))
                .isEqualTo(OpeningStatus.OPEN_NOW);

        // Midnight itself is the NEXT calendar day — "16:00-24:00" does not
        // carry over (unlike a genuine overnight range such as 18:00-02:00).
        ZonedDateTime midnightNextDay = WEDNESDAY_10AM.plusDays(1).withHour(0).withMinute(0);
        assertThat(OpeningHoursEvaluator.evaluate("Mo-Su 16:00-24:00", midnightNextDay))
                .isEqualTo(OpeningStatus.CLOSED);
    }

    @Test
    void zeroToTwentyFourHundred_meansOpenTheWholeDay() {
        // The other real-world use of "24:00": marking a full 24-hour day.
        // Mapping "24:00" to LocalTime.MIDNIGHT instead of LocalTime.MAX
        // would make start==end and read this as "never open" — the actual
        // bug this test guards against, distinct from the crash itself.
        assertThat(OpeningHoursEvaluator.evaluate("Mo-Su 00:00-24:00", WEDNESDAY_10AM.withHour(3)))
                .isEqualTo(OpeningStatus.OPEN_NOW);
        assertThat(OpeningHoursEvaluator.evaluate("Mo-Su 00:00-24:00", WEDNESDAY_10AM.withHour(23).withMinute(58)))
                .isEqualTo(OpeningStatus.OPEN_NOW);
    }

    @Test
    void aNonStandardTwentyFourHourValue_isUnknown_notAThrow() {
        // "24:30" isn't a real OSM value; must degrade to UNKNOWN, not crash.
        assertThat(OpeningHoursEvaluator.evaluate("Mo-Su 16:00-24:30", WEDNESDAY_10AM))
                .isEqualTo(OpeningStatus.UNKNOWN);
    }

    @Test
    void garbledNumericTimes_degradeToUnknown_neverThrow() {
        // Out-of-range digits that still LOOK time-shaped ("99:99") must not
        // reach LocalTime.parse at all, let alone throw past this method.
        assertThat(OpeningHoursEvaluator.evaluate("Mo-Fr 99:99-100:00", WEDNESDAY_10AM))
                .isEqualTo(OpeningStatus.UNKNOWN);
    }

    @Test
    void unsupportedSyntax_isUnknown_neverGuessed() {
        // Public-holiday selectors, "sunrise", month/date ranges and free
        // text are all outside this evaluator's deliberately limited scope
        // — see OpeningHoursEvaluator's class comment.
        assertThat(OpeningHoursEvaluator.evaluate("PH off", WEDNESDAY_10AM)).isEqualTo(OpeningStatus.UNKNOWN);
        assertThat(OpeningHoursEvaluator.evaluate("sunrise-sunset", WEDNESDAY_10AM)).isEqualTo(OpeningStatus.UNKNOWN);
        assertThat(OpeningHoursEvaluator.evaluate("Jan-Mar 09:00-17:00", WEDNESDAY_10AM)).isEqualTo(OpeningStatus.UNKNOWN);
        assertThat(OpeningHoursEvaluator.evaluate("Mo-Fr 09:00-17:00 open \"call ahead\"", WEDNESDAY_10AM))
                .isEqualTo(OpeningStatus.UNKNOWN);
    }
}
