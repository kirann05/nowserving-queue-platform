# Testing

## Verified run

[Main-branch CI run 36164270337](https://github.com/kirann05/nowserving-queue-platform/actions/runs/36164270337), commit `8b37305`, passed on September 25, 2026: **137 tests across 20 JUnit XML reports, zero failures, errors, or skipped tests**. Frontend lint/build and the backend container build also passed. Frontend lint retains seven warnings. The compatible dependency update reports zero known npm audit advisories at the time of this check.

## Reproduce the checks

With Java 21 and a running Docker engine:

```sh
cd backend
bash gradlew test --no-daemon
```

Testcontainers starts PostgreSQL and Redis. Reports are written to `backend/build/reports/tests/test/` and JUnit XML to `backend/build/test-results/test/`.

With Node 22:

```sh
cd frontend
npm ci
npm run lint
npm run build
```

## Coverage areas

The backend suite covers authentication, tenant isolation, concurrent queue advancement, idempotent joins, reservations, discovery, routing fallbacks, rate limiting, and notification adapters. Fake external providers keep the checks repeatable without live credentials.

The root [CI workflow](../.github/workflows/ci.yml) runs backend tests and frontend lint/build checks. Its uploaded XML reports are the source of truth for test totals. Historical README totals are not a current CI result.

## Not yet verified

- No repeatable load-test result or latency target is presented as a measurement.
- No automated browser end-to-end suite.
- No real-device push rendering or live SMS-delivery verification.
- Local backend execution during the September 25 documentation review was blocked by an unavailable Docker daemon.

Before publishing performance numbers, record the commit, hardware, dataset, concurrency, warm-up, test duration, error rate, and latency percentiles. Keep targets separate from observed results.
