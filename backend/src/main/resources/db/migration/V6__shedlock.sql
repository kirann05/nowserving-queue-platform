-- V6 — distributed scheduler locking.
--
-- THE PROBLEM: @Scheduled runs on every instance. With 2 replicas, the
-- Leave-Now sweep and the grace-period sweep each fire twice a minute — double
-- the routing calls (which cost money) and double the work.
--
-- It was never a CORRECTNESS bug, because every alert is idempotent: the
-- alerts table is checked before sending. But "harmless duplication" is a
-- promise that only holds while every future job is also idempotent, and
-- that is not a promise worth betting on.
--
-- ShedLock stores a row per job here. An instance may only run a job if it
-- can claim that row, so exactly one replica does the work. Postgres rather
-- than Redis on purpose: our Redis is deliberately volume-less and ephemeral,
-- and a lock that can vanish is not much of a lock.
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,  -- the job's name
    lock_until TIMESTAMPTZ  NOT NULL,              -- held until this moment
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL               -- which instance holds it
);
