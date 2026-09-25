# Engineering decisions

| Decision | Why | Cost or limitation |
| --- | --- | --- |
| PostgreSQL owns queue state | Transactions and row locks protect concurrent changes | Requires a real database in integration tests |
| Idempotency keys for joins and reservations | A retry should return the existing result | Clients must retain and reuse their key |
| Redis Pub/Sub after commit | Different application instances can notify their connected clients | Pub/Sub is not a durable event log |
| Polling alongside WebSockets | Reconnects should not leave a frozen ticket | Additional read traffic |
| Token-based customer access | Customers can join without creating accounts | Anyone with the token can exercise its access; links need protection |
| Owner JWT and tenant-scoped queries | Owner operations stay within the authenticated business | Signing keys and token handling need deployment discipline |
| Routing provider abstraction | TomTom failures can fall back to a labelled estimate | Distance estimates do not represent live traffic |

See the service and repository tests for executable examples. These are design choices, not claims of measured production scale.
