package com.nowserving.places;

/**
 * One restaurant-shaped point of interest from an EXTERNAL places source
 * (today: OpenStreetMap via Overpass). Deliberately narrow — only what the
 * discovery UI can use — so a raw Overpass/OSM response never leaks past
 * {@link OverpassPlacesProvider}. If the provider changes tomorrow, this
 * shape doesn't have to.
 *
 * Not to be confused with {@code PublicDtos.VenueSummary}: a VenueSummary is
 * a NowServing tenant with a real queue and a joinToken. A NearbyPlace is
 * just "OSM thinks a restaurant exists here" — it must never be handed a
 * joinToken, because nobody can join a line that doesn't exist.
 */
public record NearbyPlace(
        /** The source's own id (an OSM node id), so the same place is stable
         *  across repeated searches even though it has no NowServing identity. */
        String externalId,
        String name,
        double latitude,
        double longitude,
        /** Null when the source has no cuisine tag. */
        String cuisine,
        /** Null when the source has no address tag. */
        String address,
        double distanceMiles,
        /** Which provider this came from — "OSM" today, room for a second
         *  source later without changing every caller. */
        String source,
        /**
         * OSM's raw {@code opening_hours} tag, unparsed. Null when the source
         * has no such tag. Kept as the raw string (not a pre-computed
         * open/closed boolean) because "is it open" is a function of the
         * CURRENT time, and this record gets cached for up to 20 minutes —
         * baking in a computed answer would let it go stale exactly at the
         * moment it matters most, right around opening or closing time. See
         * {@link OpeningHoursEvaluator} for where it's actually evaluated.
         */
        String openingHours) {
}
