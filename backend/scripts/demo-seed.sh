#!/usr/bin/env bash
#
# demo-seed.sh — put the local stack into a known, demo-ready state.
#
# Idempotent AND convergent: however many times you run it, and whatever
# state you left the demo in, you end up with exactly the same thing. That
# second property is the one that matters — a script that only works on a
# fresh database is not a reset.
#
#   ./scripts/demo-seed.sh          # seed / reset
#   ./scripts/demo-seed.sh --status # show the current state, change nothing
#
# ARCHIVE, NOT DELETE. Old test queues are set CLOSED + unlisted and their
# stale tickets moved to LEFT. Every row survives, so nothing is destroyed
# and reopening a queue undoes it completely — which also means this script
# is safe to run against a database you care about.
#
# It needs no owner password: queue configuration goes through psql (these
# are local-database operations, and the cross-tenant parts could not be
# done with one owner's token anyway), while customers join through the real
# public API, so the join path itself is exercised on every run.
set -euo pipefail

API="${API:-http://localhost:8080}"
# Resolve the demo restaurant by its OWNER'S EMAIL, not by its name.
#
# Keying on the name read more nicely and was wrong: renaming the restaurant
# is a supported feature, so the first time the demo business was renamed
# (Desi Bites -> Taj Mahal Restaurant) this script stopped finding it and
# failed outright — right when a reset was most needed. A login is stable in
# a way a shopfront name explicitly is not.
DEMO_OWNER="${DEMO_OWNER:-shiva@nowserving.dev}"

# Plano, TX — OpenStreetMap has dense restaurant coverage here, so the
# "nearby" list has real external places to sit alongside ours.
LAT=33.0198
LNG=-96.6989

# Queues that are obviously scaffolding rather than a real line. Matched by
# name so the list is reviewable at a glance.
ARCHIVE_NAMES="'Cross-Instance Test','Sprint3 Demo','AJ','rajshree','Plano Test Line','Abc','Jack','Paul','Repro Line'"

say() { printf '\033[33m▸\033[0m %s\n' "$*"; }
die() { printf '\033[31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

PG=$(docker ps --format '{{.Names}}' 2>/dev/null | grep -i postgres | head -1 || true)
[ -n "$PG" ] || die "No running postgres container found."
psqlc() { docker exec "$PG" psql -qtAU nowserving -d nowserving -c "$1"; }

curl -sf "$API/public/venues" >/dev/null 2>&1 || die "Backend is not answering on $API — start it first."

BID=$(psqlc "SELECT business_id FROM owners WHERE email='${DEMO_OWNER}' LIMIT 1;")
[ -n "$BID" ] || die "No owner '${DEMO_OWNER}'. Set DEMO_OWNER to a login that exists."
DEMO_BUSINESS=$(psqlc "SELECT name FROM businesses WHERE id=${BID};")

if [ "${1:-}" = "--status" ]; then
  printf '\n  Public discovery:\n'
  curl -s "$API/public/venues" | python3 -c "
import sys, json
for v in json.load(sys.stdin):
    print(f\"    {v['businessName']} / {v['queueName']}  open={v['open']}  waiting={v['partiesWaiting']}\")"
  printf '\n  Demo business queues:\n'
  psqlc "SELECT '    '||q.id||' '||rpad(q.name,16)||' '||q.status||' listed='||q.listed_publicly
         FROM queues q WHERE q.business_id=${BID} ORDER BY q.id;"
  exit 0
fi

say "Demo restaurant: ${DEMO_BUSINESS} (id ${BID}, owner ${DEMO_OWNER})"

# ---------------------------------------------------------- 1. archive junk
say "Archiving leftover test queues (CLOSED + unlisted — nothing deleted)"
psqlc "UPDATE queues SET status='CLOSED', listed_publicly=false WHERE name IN (${ARCHIVE_NAMES});" >/dev/null

# Nobody should be left standing in a line that is no longer running. LEFT is
# the customer-gave-up terminal state, so the row stays for history but stops
# counting as active anywhere.
say "Retiring stale tickets on archived queues"
psqlc "UPDATE queue_entries SET status='LEFT'
        WHERE status IN ('WAITING','CALLED')
          AND queue_id IN (SELECT id FROM queues WHERE status='CLOSED' AND listed_publicly=false);" >/dev/null

# ------------------------------------------------- 2. only the demo is public
say "Unlisting every other business from public discovery"
psqlc "UPDATE queues SET listed_publicly=false WHERE business_id <> ${BID};" >/dev/null

# ------------------------------------------------------- 3. the two demo lines
say "Configuring Walk-ins and Dinner Service"
psqlc "UPDATE queues SET status='OPEN', listed_publicly=true, station_count=1, default_service_minutes=8,
              venue_latitude=${LAT}, venue_longitude=${LNG}, time_zone='America/Chicago',
              allow_remote_join=true, max_remote_join_miles=25, allow_qr_join=true
        WHERE business_id=${BID} AND name='Walk-ins';" >/dev/null

psqlc "INSERT INTO queues (business_id,name,join_token,status,station_count,default_service_minutes,
                           created_at,listed_publicly,venue_latitude,venue_longitude,time_zone,
                           reservations_enabled,opening_time,closing_time,slot_minutes,slot_capacity,
                           allow_remote_join,max_remote_join_miles,allow_qr_join,
                           grace_minutes,bump_places,safety_buffer_minutes)
        SELECT ${BID},'Dinner Service',gen_random_uuid()::text,'OPEN',2,12,now(),true,${LAT},${LNG},'America/Chicago',
               true,'11:00','22:00',30,4,true,25,true,10,2,5
         WHERE NOT EXISTS (SELECT 1 FROM queues WHERE business_id=${BID} AND name='Dinner Service');" >/dev/null

psqlc "UPDATE queues SET status='OPEN', listed_publicly=true, reservations_enabled=true,
              opening_time='11:00', closing_time='22:00', slot_minutes=30, slot_capacity=4,
              venue_latitude=${LAT}, venue_longitude=${LNG}, time_zone='America/Chicago'
        WHERE business_id=${BID} AND name='Dinner Service';" >/dev/null

WALKINS=$(psqlc "SELECT join_token FROM queues WHERE business_id=${BID} AND name='Walk-ins';")
DINNER=$(psqlc  "SELECT join_token FROM queues WHERE business_id=${BID} AND name='Dinner Service';")

# ----------------------------------------------------- 4. deterministic guests
# Retire whoever is standing there now, THEN join the cast fresh. Without the
# first half a re-run would append to a line that already had people, so
# "reset" would drift a little further from the demo every time.
say "Resetting the line and seeding demo customers"
psqlc "UPDATE queue_entries SET status='LEFT'
        WHERE status IN ('WAITING','CALLED')
          AND queue_id IN (SELECT id FROM queues WHERE business_id=${BID});" >/dev/null

join_guest() { # token, name, party
  curl -s -o /dev/null -X POST "$API/public/queues/$1/entries" \
    -H 'Content-Type: application/json' \
    -d "{\"customerName\":\"$2\",\"partySize\":$3}" || true
}
join_guest "$DINNER"  "Maya"   2
join_guest "$DINNER"  "Daniel" 4
join_guest "$WALKINS" "Priya"  2
join_guest "$WALKINS" "Alex"   3

PUBLIC_TOKEN=$(psqlc "SELECT public_token FROM businesses WHERE id=${BID};")

cat <<EOF

  Demo ready — ${DEMO_BUSINESS}

    Restaurant QR   http://localhost:5173/qr/${PUBLIC_TOKEN}
    Dinner Service  http://localhost:5173/v/${DINNER}   (Maya, Daniel · reservations on)
    Walk-ins        http://localhost:5173/v/${WALKINS}   (Priya, Alex)
    Location        Plano, TX (${LAT}, ${LNG})

  Re-run at any time to reset to exactly this state.
EOF
