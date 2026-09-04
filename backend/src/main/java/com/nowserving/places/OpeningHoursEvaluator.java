package com.nowserving.places;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads OSM's {@code opening_hours} tag well enough to answer "is this place
 * open right now?" — and admits when it can't.
 *
 * THE OSM opening_hours SPEC IS HUGE: public holidays, school holidays,
 * sunrise/sunset, month and week-number selectors, free-text comments in
 * quotes, "easter", "+" for "at least until"... A complete implementation is
 * a project in itself (that's exactly why maintained libraries like
 * ch.poole's opening-hours-parser exist). Pulling in a new Maven dependency
 * for one feature deserved its own deliberate call, and this codebase's
 * existing pattern — see TomTomTravelTimeProvider and TwilioSmsSender —
 * is to reach for a small hand-written adapter over a dependency when the
 * slice of behaviour actually needed is narrow. So: THIS PARSER IS
 * DELIBERATELY LIMITED, on purpose, and says so out loud.
 *
 * SUPPORTED (the overwhelming majority of real-world tags):
 *   24/7
 *   Mo-Fr 09:00-17:00
 *   Mo,We,Fr 09:00-12:00,13:00-18:00
 *   Sa-Su 10:00-14:00
 *   Mo-Fr 09:00-18:00; Sa off          (";"-separated rules, LAST ONE WINS
 *                                        for any day both rules mention)
 *   18:00-02:00                       (overnight — end before start wraps
 *                                        past midnight)
 *   Mo-Tu 16:00-24:00                 ("24:00" is OSM's own idiom for
 *                                        midnight-at-the-end-of-this-day —
 *                                        common enough on real restaurant
 *                                        tags that treating it as malformed
 *                                        would make this parser wrong for a
 *                                        large share of genuine data. Read
 *                                        as equivalent to 00:00 and handled
 *                                        by the same overnight-wrap logic as
 *                                        18:00-02:00.)
 *   (no day prefix = applies every day)
 *
 * NOT SUPPORTED — anything else (holiday selectors, month/date ranges,
 * "sunrise"/"sunset", free-text comments, week numbers, "+", and so on)
 * makes the WHOLE tag UNKNOWN rather than guessed at. Partial credit on a
 * malformed rule is how you end up confidently telling someone a restaurant
 * is open when it's a bank holiday and it isn't.
 *
 * TIMEZONE: evaluated against the server's own clock/zone, not the
 * restaurant's actual location. Getting the real answer needs a
 * coordinates-to-timezone lookup this feature doesn't have; documented here
 * rather than silently assumed away.
 */
public final class OpeningHoursEvaluator {

    private OpeningHoursEvaluator() {}

    private static final Pattern DAY_TOKEN =
            Pattern.compile("^(Mo|Tu|We|Th|Fr|Sa|Su)(-(Mo|Tu|We|Th|Fr|Sa|Su))?(,(Mo|Tu|We|Th|Fr|Sa|Su)(-(Mo|Tu|We|Th|Fr|Sa|Su))?)*$");
    private static final Pattern TIME_RANGE = Pattern.compile("^([0-2]\\d:[0-5]\\d)-([0-2]\\d:[0-5]\\d)$");
    private static final Map<String, DayOfWeek> DAY_CODES = Map.of(
            "Mo", DayOfWeek.MONDAY, "Tu", DayOfWeek.TUESDAY, "We", DayOfWeek.WEDNESDAY,
            "Th", DayOfWeek.THURSDAY, "Fr", DayOfWeek.FRIDAY, "Sa", DayOfWeek.SATURDAY,
            "Su", DayOfWeek.SUNDAY);

    /** One parsed rule: which days it applies to, and its time ranges (empty = "off"). */
    private record Rule(EnumSet<DayOfWeek> days, java.util.List<LocalTimeRange> ranges) {}

    private record LocalTimeRange(LocalTime start, LocalTime end) {
        boolean contains(LocalTime t) {
            if (!end.isBefore(start)) { // ordinary same-day range
                return !t.isBefore(start) && t.isBefore(end);
            }
            // Overnight: e.g. 18:00-02:00 — open from start through midnight
            // to end.
            return !t.isBefore(start) || t.isBefore(end);
        }
    }

    public static OpeningStatus evaluate(String openingHours, ZonedDateTime now) {
        if (openingHours == null || openingHours.isBlank()) {
            return OpeningStatus.UNKNOWN;
        }
        String trimmed = openingHours.trim();
        if (trimmed.equals("24/7")) {
            return OpeningStatus.OPEN_NOW;
        }

        try {
            return evaluateInternal(trimmed, now);
        } catch (RuntimeException e) {
            // LAST-RESORT SAFETY NET. This method feeds a public API
            // response — it must never throw for ANY input, known pattern or
            // not. The specific case that forced this in (java.time rejecting
            // "24:00" as an hour-of-day, even though it's a legal, common OSM
            // idiom) is now handled properly below, but the broader lesson —
            // "unhandled input reaching a real user as a 500" — is exactly
            // the failure mode every other external-data adapter in this
            // codebase (OverpassPlacesProvider, TomTomTravelTimeProvider)
            // already guards against with a catch-all. This evaluator is no
            // less a parser of untrusted third-party data than they are.
            return OpeningStatus.UNKNOWN;
        }
    }

    private static OpeningStatus evaluateInternal(String trimmed, ZonedDateTime now) {
        java.util.List<Rule> rules;
        try {
            rules = parseRules(trimmed);
        } catch (UnsupportedTagException e) {
            return OpeningStatus.UNKNOWN;
        }
        if (rules.isEmpty()) {
            return OpeningStatus.UNKNOWN;
        }

        // Later rules override earlier ones for any day they both mention —
        // exactly the OSM spec's own precedence rule, which is what makes
        // "Mo-Fr 09:00-18:00; Sa off" mean Saturday is closed rather than
        // ambiguous.
        Map<DayOfWeek, java.util.List<LocalTimeRange>> byDay = new EnumMap<>(DayOfWeek.class);
        for (Rule rule : rules) {
            for (DayOfWeek day : rule.days()) {
                byDay.put(day, rule.ranges());
            }
        }

        DayOfWeek today = now.getDayOfWeek();
        LocalTime nowTime = now.toLocalTime();

        // An overnight range from YESTERDAY can still be open right now
        // (e.g. it's 01:00 and yesterday's rule was "18:00-02:00").
        java.util.List<LocalTimeRange> todayRanges = byDay.get(today);
        if (todayRanges != null && todayRanges.stream().anyMatch(r -> r.contains(nowTime))) {
            return OpeningStatus.OPEN_NOW;
        }
        java.util.List<LocalTimeRange> yesterdayRanges = byDay.get(today.minus(1));
        if (yesterdayRanges != null
                && yesterdayRanges.stream().anyMatch(r -> r.end().isBefore(r.start()) && r.contains(nowTime))) {
            return OpeningStatus.OPEN_NOW;
        }

        // We understood the tag well enough to know it names at least one
        // day with a definite schedule — silence for today just means closed
        // today, which is a confident answer, not a guess.
        return OpeningStatus.CLOSED;
    }

    private static java.util.List<Rule> parseRules(String tag) throws UnsupportedTagException {
        java.util.List<Rule> rules = new java.util.ArrayList<>();
        for (String rulePart : tag.split(";")) {
            String part = rulePart.trim();
            if (part.isEmpty()) continue;

            String[] tokens = part.split("\\s+", 2);
            String dayToken;
            String timeToken;
            if (tokens.length == 2 && DAY_TOKEN.matcher(tokens[0]).matches()) {
                dayToken = tokens[0];
                timeToken = tokens[1];
            } else {
                // No day prefix — applies to every day of the week.
                dayToken = "Mo-Su";
                timeToken = part;
            }

            EnumSet<DayOfWeek> days = parseDays(dayToken);

            if (timeToken.equalsIgnoreCase("off") || timeToken.equalsIgnoreCase("closed")) {
                rules.add(new Rule(days, java.util.List.of()));
                continue;
            }

            java.util.List<LocalTimeRange> ranges = new java.util.ArrayList<>();
            for (String rangeStr : timeToken.split(",")) {
                Matcher m = TIME_RANGE.matcher(rangeStr.trim());
                if (!m.matches()) {
                    // Anything we don't recognise (holiday codes, "sunrise",
                    // free text, malformed input) invalidates the WHOLE tag —
                    // see the class comment on why partial trust is unsafe.
                    throw new UnsupportedTagException();
                }
                ranges.add(new LocalTimeRange(parseTimeOfDay(m.group(1)), parseTimeOfDay(m.group(2))));
            }
            rules.add(new Rule(days, ranges));
        }
        return rules;
    }

    /**
     * "HH:MM" per the TIME_RANGE regex, EXCEPT that plain {@link LocalTime}
     * rejects hour 24 outright ({@code LocalTime.parse("24:00")} throws —
     * this was the actual production bug: an uncaught DateTimeException from
     * exactly this call turned into a 500 on the whole nearby-discovery
     * endpoint the moment a real restaurant's tag used "24:00").
     *
     * OSM's own spec allows "24:00" as the end of the current day — mapped
     * here to {@link LocalTime#MAX} (23:59:59.999999999) rather than
     * midnight. That keeps "16:00-24:00" on the ordinary same-day branch of
     * {@link LocalTimeRange#contains} (open from 4pm to the end of today) and
     * — unlike mapping it to 00:00 — also gets "00:00-24:00" (a full day)
     * right: 00:00 would make start==end and read as "never open" once
     * treated as a same-day range, whereas MAX correctly spans the whole day.
     */
    private static LocalTime parseTimeOfDay(String hhmm) throws UnsupportedTagException {
        if (hhmm.startsWith("24:")) {
            if (!hhmm.equals("24:00")) {
                throw new UnsupportedTagException(); // "24:30" etc. isn't a real OSM value
            }
            return LocalTime.MAX;
        }
        try {
            return LocalTime.parse(hhmm);
        } catch (java.time.format.DateTimeParseException e) {
            throw new UnsupportedTagException();
        }
    }

    private static EnumSet<DayOfWeek> parseDays(String dayToken) throws UnsupportedTagException {
        EnumSet<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (String segment : dayToken.split(",")) {
            String[] range = segment.split("-");
            DayOfWeek from = DAY_CODES.get(range[0]);
            if (from == null) throw new UnsupportedTagException();
            if (range.length == 1) {
                days.add(from);
            } else {
                DayOfWeek to = DAY_CODES.get(range[1]);
                if (to == null) throw new UnsupportedTagException();
                DayOfWeek d = from;
                days.add(d);
                while (d != to) {
                    d = d.plus(1);
                    days.add(d);
                }
            }
        }
        return days;
    }

    /** Signals "this tag uses syntax outside the documented subset" — always
     *  caught and converted to {@link OpeningStatus#UNKNOWN}, never surfaced. */
    private static final class UnsupportedTagException extends Exception {}
}
