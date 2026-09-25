# Core flows

These diagrams summarize the implementation. They do not imply exactly-once event delivery or measured performance.

## Joining a queue

```mermaid
sequenceDiagram
  actor Customer
  participant API as Spring API
  participant DB as PostgreSQL
  participant Events as Queue event listener
  Customer->>API: Join token, party details, idempotency key
  API->>DB: Validate queue and perform transactional join
  DB-->>API: Existing or newly created ticket
  API-->>Customer: Ticket token and queue state
  DB-->>Events: Transaction committed
  Events->>Events: Publish refreshed queue state
```

## Advancing a queue

```mermaid
sequenceDiagram
  actor Owner
  participant API as Owner API
  participant DB as PostgreSQL
  Owner->>API: Advance with owner JWT
  API->>DB: Tenant-scoped lookup and row locking
  DB-->>API: Locked eligible entry
  API->>DB: Mark served and commit
  API-->>Owner: Updated result
  Note over API,DB: Queue change events are handled after commit
```

## Cross-instance updates

```mermaid
sequenceDiagram
  participant A as Application A
  participant R as Redis Pub/Sub
  participant B as Application B
  participant C as Customer browser
  A->>R: Publish committed state change
  R->>B: Deliver event
  B->>C: STOMP update
  C->>B: Poll authoritative state if sockets are unavailable
```

Redis distributes transient notifications; PostgreSQL remains the durable source of truth. Polling repairs missed updates rather than relying on replay from Pub/Sub.

## Leave Now

```mermaid
sequenceDiagram
  participant C as Customer
  participant API as Leave Now service
  participant Route as Travel provider
  C->>API: Ticket and consented location
  API->>API: Estimate turn from queue state
  API->>Route: Request travel estimate
  alt Routing available
    Route-->>API: Traffic-aware travel time
  else Provider unavailable
    Route-->>API: Labelled distance-based fallback
  end
  API->>API: Expected turn minus travel and safety buffer
  API-->>C: Departure advice and estimate quality
```
