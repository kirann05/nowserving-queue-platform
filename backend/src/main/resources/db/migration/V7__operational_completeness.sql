-- V7 — the gaps between "backend feature-complete" and "an owner can run a
-- whole day without curl".

-- Close/reopen with an AUDIT TRAIL: who shut the queue, when, and why.
-- "The queue was closed" is a fact someone will eventually dispute.
ALTER TABLE queues ADD COLUMN closed_at    TIMESTAMPTZ;
ALTER TABLE queues ADD COLUMN closed_by    VARCHAR(255);
ALTER TABLE queues ADD COLUMN close_reason VARCHAR(255);

-- Join idempotency, same pattern as reservations: a double-tap or a retry on
-- flaky wifi returns the ORIGINAL ticket instead of minting a second one.
ALTER TABLE queue_entries ADD COLUMN idempotency_key VARCHAR(100);
CREATE UNIQUE INDEX uq_entries_idempotency
    ON queue_entries (queue_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- Lightweight estimate feedback (ACCURATE | CLOSE | INACCURATE) — the seed
-- data for ever improving the estimator.
ALTER TABLE queue_entries ADD COLUMN feedback VARCHAR(20);

-- History views sort by completion time.
CREATE INDEX idx_entries_queue_served ON queue_entries (queue_id, served_at DESC);
