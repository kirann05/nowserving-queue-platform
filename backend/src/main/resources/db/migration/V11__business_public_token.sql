-- A PERMANENT, restaurant-level public token.
--
-- Until now the only public identity a customer could be handed was a
-- queue's join_token, so every printed QR code was tied to one queue. Delete
-- that queue, close it for the season, or recreate it under a new name, and
-- every poster and table card pointing at it becomes dead paper.
--
-- This token belongs to the BUSINESS and never changes, which is the whole
-- point: it is the thing that gets printed. Queues remain free to come and
-- go behind it.
--
-- join_token is deliberately left exactly where it is. Every already-printed
-- QR encodes one, and /j/{joinToken}, /v/{joinToken} and /b/{joinToken} must
-- keep resolving forever.

ALTER TABLE businesses ADD COLUMN public_token VARCHAR(64);

-- Backfill every existing restaurant so nobody has to re-save anything to
-- get a QR. gen_random_uuid() is built in from PG13 (pgcrypto not needed).
UPDATE businesses SET public_token = gen_random_uuid()::text WHERE public_token IS NULL;

ALTER TABLE businesses ALTER COLUMN public_token SET NOT NULL;
ALTER TABLE businesses ADD CONSTRAINT uq_businesses_public_token UNIQUE (public_token);
