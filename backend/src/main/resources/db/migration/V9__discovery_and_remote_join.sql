-- V9 — Discovery + owner-controlled remote joining + notification choice.
--
-- Until now the ONLY way into a queue was possession of the join link, which
-- meant the customer had to already be standing at the venue with a QR code
-- in front of them. That made "leave now" oddly circular: we told people when
-- to set off from home, but they could only join once they'd arrived.
--
-- Discovery closes that loop — find a restaurant, see the wait, join from the
-- sofa — and immediately creates a problem the owner must be allowed to
-- solve: somebody 45 miles away should probably not be holding a place in a
-- 20-minute line. So remote joining becomes POLICY, set per restaurant,
-- rather than a global constant baked into the code.

-- ---------------------------------------------------------------------------
-- Discovery
-- ---------------------------------------------------------------------------

-- Being findable is a CHOICE, not a side-effect of signing up. A private
-- staff-only line (a back-office queue, a test queue) must never surface in
-- public search just because someone created it.
--
-- Existing rows default to TRUE: at this point every queue in the database is
-- a demo queue belonging to the developer, and defaulting to FALSE would make
-- the new page look broken. For a real multi-tenant launch the safe default
-- is FALSE plus a one-time opt-in prompt — see docs/ARCHITECTURE.md.
ALTER TABLE queues ADD COLUMN listed_publicly BOOLEAN NOT NULL DEFAULT TRUE;

-- ---------------------------------------------------------------------------
-- Remote joining policy (owner-controlled)
-- ---------------------------------------------------------------------------

-- Can somebody join without being here? A busy city restaurant says yes; a
-- three-table cafe that turns over every ten minutes says no.
ALTER TABLE queues ADD COLUMN allow_remote_join BOOLEAN NOT NULL DEFAULT TRUE;

-- ...and if yes, how far away is still reasonable? 50 is the CEILING the
-- product enforces, not the value every restaurant is stuck with. The
-- application validates 1..50; the CHECK constraint is the backstop, because
-- app-level validation is UX and constraints are law (same reasoning as the
-- unique email index in V1).
ALTER TABLE queues ADD COLUMN max_remote_join_miles INT NOT NULL DEFAULT 25
    CONSTRAINT chk_max_remote_miles CHECK (max_remote_join_miles BETWEEN 1 AND 50);

-- The QR code is a separate door, and it deserves its own switch: scanning it
-- is itself proof of presence, so it stays open even when remote joining is
-- off. An owner can also close it (e.g. a private event) without unlisting.
ALTER TABLE queues ADD COLUMN allow_qr_join BOOLEAN NOT NULL DEFAULT TRUE;

-- Discovery lists open, listed queues. This index keeps that page's query off
-- a full table scan once there is more than one restaurant.
CREATE INDEX idx_queues_discovery ON queues (listed_publicly, status);

-- ---------------------------------------------------------------------------
-- Notification choice (the 5th product change)
-- ---------------------------------------------------------------------------

-- Web Push is primary and needs nothing stored here — the subscription lives
-- in push_subscriptions. SMS needs a destination, so it needs a column.
--
-- This is PERSONAL DATA, unlike the coarse coordinates in Redis: it is
-- durable, identifying, and belongs to the customer rather than to us. It is
-- nullable because it is genuinely optional (FR-17's principle again: every
-- enhancement must degrade to "nothing breaks"), and it is deleted with the
-- entry it belongs to.
ALTER TABLE queue_entries ADD COLUMN phone_number VARCHAR(20);

-- How this customer wants to be reached. NONE is a real, respected answer —
-- they can keep the ticket page open and watch it live instead.
ALTER TABLE queue_entries ADD COLUMN notify_channel VARCHAR(10) NOT NULL DEFAULT 'PUSH'
    CONSTRAINT chk_notify_channel CHECK (notify_channel IN ('PUSH', 'SMS', 'NONE'));
