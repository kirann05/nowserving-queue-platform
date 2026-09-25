# Security model and release limits

- Owners authenticate with JWTs. Business-scoped lookups enforce tenant boundaries.
- Customer join, ticket, and reservation tokens are bearer capabilities. Do not log or publicly share real customer links.
- Idempotency and locking protect state consistency; neither replaces authorization.
- Location is optional. Treat location, contact details, and push subscriptions as sensitive data.
- Production must use TLS, explicit CORS origins, and a random JWT signing secret. The `prod` profile rejects the known development signing key.
- Committed database credentials are local-development placeholders, not production credentials.
- Rate limiting is in-memory and per-instance. A multi-instance deployment needs a shared policy.
- Google client IDs are public identifiers. Provider API keys and private signing material belong in a secret store.

## Pre-publication review

On September 25, 2026, Gitleaks 8.30.1 scanned all seven existing commits and reported no detected secrets. This is a bounded automated check, not a guarantee or a penetration test. Tracked filenames were also reviewed for local environment files, databases, and key material.

No live SMS, real-device push, penetration test, or production security certification is claimed. Send security reports privately to kirangandluri1@gmail.com; do not include credentials in a public issue.
