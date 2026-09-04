-- V8 — feedback grows up: 1–5 stars plus an optional free-text comment.
--
-- The old 3-way rating (ACCURATE/CLOSE/INACCURATE) asked one narrow question
-- about the estimate. Stars + a comment ask the broader one — "how was it?" —
-- and the comment is where product improvement ideas actually arrive.
-- The customer's name is already on the row (customer_name), so the feedback
-- "dataset" is simply: name, stars, comment, when — queryable per queue/day
-- via the existing history endpoint.
ALTER TABLE queue_entries ADD COLUMN rating_stars INT
    CONSTRAINT ck_rating_stars CHECK (rating_stars BETWEEN 1 AND 5);
ALTER TABLE queue_entries ADD COLUMN feedback_comment VARCHAR(1000);
ALTER TABLE queue_entries ADD COLUMN feedback_at TIMESTAMPTZ;

-- Carry the old signal forward rather than discarding it: the three levels
-- map onto the star scale conservatively.
UPDATE queue_entries SET rating_stars = CASE feedback
    WHEN 'ACCURATE'   THEN 5
    WHEN 'CLOSE'      THEN 3
    WHEN 'INACCURATE' THEN 1
    END
WHERE feedback IS NOT NULL;

ALTER TABLE queue_entries DROP COLUMN feedback;
