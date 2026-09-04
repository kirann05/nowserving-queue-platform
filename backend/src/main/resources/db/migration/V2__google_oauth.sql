-- V2__google_oauth.sql — FR-1: "register with email+password OR Google OAuth".
--
-- Flyway rule in action: V1 has already run on machines/environments, so we
-- NEVER edit it. A change to the schema is a NEW numbered file that migrates
-- existing data forward. That is what makes any environment reproducible by
-- replaying V1..Vn in order.
--
-- Two changes, both driven by the PRD's owners table (§3.3):
--   owners: ... password_hash (nullable), google_sub (nullable) ...

-- 1. A Google-only owner has NO password at all. Storing a fake/empty hash
--    would be a lie the code would eventually trust, so the column becomes
--    genuinely nullable and "has a password" becomes a real question we ask.
ALTER TABLE owners ALTER COLUMN password_hash DROP NOT NULL;

-- 2. google_sub = the "subject" claim from Google's ID token: Google's own
--    permanent, unique id for that human. We key on THIS, not on email,
--    because a person can change their Gmail address while keeping the same
--    account — the sub never changes. UNIQUE so one Google identity can never
--    end up attached to two owner rows.
ALTER TABLE owners ADD COLUMN google_sub VARCHAR(255);
ALTER TABLE owners ADD CONSTRAINT uq_owners_google_sub UNIQUE (google_sub);
-- NOTE: in Postgres, UNIQUE allows many NULLs — so every email/password owner
-- (google_sub = NULL) coexists happily. That is exactly what we want.
