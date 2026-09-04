# NowServing — Architecture & File Map

**Scope of this doc:** both repos as of **end of Sprint 6 — feature complete**.
Stack: **Java 21, Spring Boot 4.1, Gradle, PostgreSQL 16, Redis 7, Flyway,
JWT + Google OAuth, Web Push, Docker/K8s**, and **React 19 + TypeScript + Vite**.

**Against the PRD:** Sprints 1–6 ✅ · **all 17 functional requirements
implemented** · **67 tests green** · `./gradlew build` green.
Remaining work is operational, not functional — see
[SPRINT6_PREP.md](SPRINT6_PREP.md) §4.

| Sprint | Delivered |
|---|---|
| 1 | Auth (email + Google), queues, QR join, customer join, tenant isolation, concurrency-safe advance |
| 2 | WebSocket push + Redis pub/sub fan-out across instances |
| 3 | Web Push notifications, rolling-median wait estimates, circuit breaker + bulkhead |
| 4 | Reservations: derived slots, seat-constraint race safety, idempotency keys, `targetServeTime` abstraction |
| 5 | Leave-Now engine: travel-time port, cost guards, coarse/ephemeral location, leave-by alerts, grace/bump policy |
| 6 | Dockerfile, K8s manifests, GitHub Actions CI, Actuator + Prometheus metrics |

> Reading rhythm used throughout: **WHAT it is → WHY it exists → WHAT BREAKS without it.**

---

## 1. Directory tree

```
backend/
├── build.gradle                  # dependencies + build config
├── settings.gradle               # project name
├── docker-compose.yml            # Postgres 16 (host 5433) + Redis 7 (host 6380)
├── gradlew / gradlew.bat         # Gradle wrapper scripts
├── gradle/wrapper/               # pinned Gradle version
├── .gitignore / .gitattributes
├── README.md
├── docs/
│   └── ARCHITECTURE.md           # this file
└── src/
    ├── main/
    │   ├── java/com/nowserving/
    │   │   ├── NowservingBackendApplication.java   # entry point
    │   │   ├── config/        SecurityConfig
    │   │   ├── controller/    Health, Auth, Queue, PublicQueue
    │   │   ├── dto/           AuthDtos, QueueDtos, PublicDtos
    │   │   ├── entity/        Business, Owner, Queue, QueueEntry, +2 enums
    │   │   ├── exception/     ApiException family + GlobalExceptionHandler
    │   │   ├── notification/  NotificationChannel (port), WebPushChannel,
    │   │   │                  NotificationListener, NotificationDispatcher
    │   │   ├── realtime/      RealtimePublisher (port), Redis publisher +
    │   │   │                  subscriber, RealtimeMessage envelope
    │   │   ├── repository/    Business, Owner, Queue, QueueEntry
    │   │   ├── security/      JwtService, JwtAuthenticationFilter,
    │   │   │                  RateLimitFilter, AuthenticatedOwner,
    │   │   │                  GoogleIdTokenVerifier (+ OIDC adapter)
    │   │   └── service/       AuthService, QueueService, PublicQueueService,
    │   │                      QueueChangedEvent, QueueEventsBroadcaster
    │   └── resources/
    │       ├── application.yml
    │       └── db/migration/V1__init.sql
    └── test/java/com/nowserving/
        ├── AbstractIntegrationTest.java   # Testcontainers base
        ├── AuthIntegrationTest.java
        ├── QueueTenantIsolationTest.java
        ├── PublicJoinPositionTest.java
        ├── AdvanceConcurrencyTest.java    # the NS-8 star
        └── RateLimitFilterTest.java
```

---

## 2. The layering — what each layer is *for*

A request flows **top to bottom**, and each layer is only allowed to talk to
its immediate neighbour below. That one rule is what keeps the code navigable.

```
HTTP request
   │
   ▼
[security]   filters — is this caller allowed in? who are they?
   │
   ▼
[controller] HTTP ↔ Java. Bind JSON, validate, call a service. No logic.
   │
   ▼
[service]    business rules + transaction boundaries. The "brain".
   │
   ▼
[repository] the only layer that talks to the database.
   │
   ▼
[entity]     Java objects mapped to DB rows.
   │
   ▼
PostgreSQL
```

`dto`, `exception`, `config` are **cross-cutting** — used by several layers.

**Analogy — a restaurant:**
- `controller` = waiter (takes your order, never cooks)
- `service` = chef (all the actual decisions happen here)
- `repository` = pantry clerk (only one allowed to touch the storeroom)
- `entity` = the labelled containers in the storeroom
- `dto` = the plated dish handed back (not the raw pantry container)
- `security` = the doorman
- `exception` = the standard "we're sorry" card every mistake gets written on

**What breaks if you ignore the layering:** put a DB query in a controller and
you can't reuse it or test it without spinning up HTTP; put business logic in a
repository and it runs outside the transaction; return an `entity` instead of a
`dto` and you leak `passwordHash` to the client and hit
`LazyInitializationException`. The layering isn't ceremony — each violation has
a specific failure.

### The three stereotype annotations

| Annotation | Marks a class as | What actually goes wrong if misused |
|---|---|---|
| `@RestController` | HTTP endpoint; return value → JSON | Put logic here → untestable without HTTP, duplicated across endpoints, runs outside any transaction |
| `@Service` | business logic + `@Transactional` boundary | Put HTTP concerns here → service can't be reused by a non-HTTP caller (a scheduled job, a WebSocket) |
| `@Repository` | data access; also translates DB exceptions into Spring's `DataAccessException` | Put business rules here → they run per-query, can't be unit-tested, bypass the transaction the service owns |

All three are `@Component` underneath — Spring creates one instance
("bean") of each at startup and **injects** it wherever it's needed (see
constructor injection via Lombok's `@RequiredArgsConstructor`). The stereotypes
differ in *intent* and in the extra behaviour Spring attaches (`@Repository`'s
exception translation, `@Service`'s transaction weaving).

---

## 3. File map — backend

### Layer: `security` (the doorman)
**What this layer is for:** decide *whether* a request may proceed and *who* is
making it — before any controller runs.

| File | What it is | Why it exists | Breaks if gone/wrong |
|---|---|---|---|
| `security/AuthenticatedOwner.java` | Immutable record `{ownerId, businessId, email}` — the "who am I" carried through a request | So every service reads `businessId` from a **trusted** source, not the request body (NS-4) | Tenant isolation collapses — you'd trust client-supplied ids |
| `security/JwtService.java` | Issues + validates JWTs (HS256, 24h) | Stateless auth: the token itself proves identity, no server session | Login can't mint tokens; forged tokens go undetected |
| `security/JwtAuthenticationFilter.java` | Reads `Authorization: Bearer …`, validates, puts identity in the `SecurityContext` | Bridges "a raw HTTP header" → "an authenticated principal Spring understands" | Protected endpoints can't see who's calling; `@AuthenticationPrincipal` is null |
| `security/RateLimitFilter.java` | In-memory fixed-window counter per IP on `/public/**`; 429 over the limit | NS-5: the public join endpoint is unauthenticated and *will* be spammed | Anyone can flood a queue with junk entries |
| `config/SecurityConfig.java` | The filter chain: CSRF off, sessions stateless, which routes are public vs. protected, 401 shape | One place that defines the security posture of the whole API | Everything 401s, or worse, everything is public |

> **Honesty note:** `JwtAuthenticationFilter` is registered **twice** by default
> (once as a `@Component`, once in the chain). `SecurityConfig` has a
> `FilterRegistrationBean … setEnabled(false)` to suppress the duplicate. This
> is a real Spring gotcha, handled — but it's the kind of thing you must be able
> to explain, not just copy.

### Layer: `controller` (the waiter)
**What this layer is for:** translate HTTP ↔ Java. Bind and validate the
request, call exactly one service method, return a DTO. If a controller grows an
`if` with business meaning, it's in the wrong layer.

| File | What it is | Why it exists | Breaks if gone/wrong |
|---|---|---|---|
| `controller/HealthController.java` | `GET /health` → `{status, dbConnected, version}` | NS-0: liveness + DB reachability probe | No cheap "is it up?" check for you or monitoring |
| `controller/AuthController.java` | `POST /auth/signup`, `POST /auth/login`, `GET /me` | NS-2/NS-3 public auth surface | No way to create accounts or log in |
| `controller/QueueController.java` | `POST/GET /queues`, `GET /queues/{id}`, `.../entries`, `.../advance`, `DELETE /entries/{id}` | NS-4/NS-7/NS-8 owner+staff surface | Owners can't manage queues or serve the line |
| `controller/PublicQueueController.java` | `POST /public/queues/{joinToken}/entries`, `GET /public/entries/{entryToken}` | NS-5/NS-6 no-auth customer surface | Customers can't join or check position |

### Layer: `dto` (the plated dish)
**What this layer is for:** define the **API contract** as plain, immutable data
(`record`s), separate from the DB shape.

**Why a `dto` package exists at all — why not return entities?** Three concrete failures if you return entities directly:
1. **Leakage** — `Owner` has `passwordHash`; serialize the entity and you ship the hash to the browser.
2. **`LazyInitializationException`** — an entity's lazy relationships (`owner.business`) load on access; serialize it *after* the transaction closes and Jackson touches a lazy field with no DB session → runtime crash.
3. **Coupling** — the JSON your clients depend on becomes your DB schema. Rename a column and you break every client. A DTO lets the two evolve independently.

| File | What it is | Why it exists | Breaks if gone/wrong |
|---|---|---|---|
| `dto/AuthDtos.java` | `SignupRequest/Response`, `LoginRequest/Response`, `MeResponse` (+ validation annotations) | Contract for auth; `@Email/@NotBlank/@Size` live here | No input validation; entity leakage |
| `dto/QueueDtos.java` | `CreateQueueRequest`, `QueueResponse`, `WaitingEntryResponse`, `AdvanceResponse` | Contract for owner/staff endpoints | Same |
| `dto/PublicDtos.java` | `JoinRequest`, `JoinResponse`, `PositionResponse` | Contract for customer endpoints | Same |

### Layer: `service` (the chef)
**What this layer is for:** the actual decisions and the `@Transactional`
boundaries. This is where "green" code and "senior" code look most different.

| File | What it is | Why it exists | Breaks if gone/wrong |
|---|---|---|---|
| `service/AuthService.java` | Signup (Business+Owner in one txn, BCrypt), login (constant error to prevent user-enumeration), `me` | NS-2/NS-3 logic | Half-created accounts on failure; password/enumeration leaks |
| `service/QueueService.java` | Create/list/detail (tenant-scoped), staff view, **advance** (the lock), no-show | NS-4/NS-7/NS-8 logic | Cross-tenant leaks; the double-serve race |
| `service/PublicQueueService.java` | Join (reject if closed), derive position, estimate wait | NS-5/NS-6 logic | Customers join closed queues; wrong positions |

### Layer: `repository` (the pantry clerk)
**What this layer is for:** the *only* code that touches the DB. Spring Data
generates the implementation from the interface + method names at startup.

| File | What it is | Why it exists | Breaks if gone/wrong |
|---|---|---|---|
| `repository/BusinessRepository.java` | `JpaRepository<Business, Long>` | CRUD for businesses | Can't persist businesses |
| `repository/OwnerRepository.java` | + `findByEmail`, `existsByEmail` | Login lookup + duplicate check | No login; no duplicate guard |
| `repository/QueueRepository.java` | + `findByBusinessIdOrderBy…`, **`findByIdAndBusinessId`**, `findByJoinToken` | `findByIdAndBusinessId` **is** tenant isolation (NS-4) | Cross-tenant access |
| `repository/QueueEntryRepository.java` | `countWaitingAhead` (position), **`lockNextWaiting`** (`FOR UPDATE SKIP LOCKED`), staff-list + count queries | Position math + the NS-8 concurrency fix | Wrong positions; double-serve |

### Layer: `entity` (the labelled containers)
**What this layer is for:** Java classes mapped to DB tables (`@Entity`). One
instance ≈ one row.

| File | What it is | Why it exists | Breaks if gone/wrong |
|---|---|---|---|
| `entity/Business.java` | The tenant | Everything scopes to a business | No multi-tenancy |
| `entity/Owner.java` | Login account; `@ManyToOne` → Business | Who signs in | No auth subject |
| `entity/Queue.java` | A line; `join_token`, `status`, `stationCount`, `defaultServiceMinutes` | The thing customers join | No queues |
| `entity/QueueEntry.java` | One ticket; `entry_token`, `status`, `joined_at` — **no `position` column** | Position is *derived*, never stored | Stored positions would need mass-rewrites and would race |
| `entity/QueueStatus.java` / `EntryStatus.java` | Enums (`OPEN/CLOSED`, `WAITING/CALLED/SERVED/NO_SHOW`) stored as strings | Readable, reorder-safe status | Ordinal storage corrupts on reorder |

### Layer: `exception` (the standard apology card)
**What this layer is for:** turn errors into **one** consistent JSON shape.

| File | What it is | Why it exists | Breaks if gone/wrong |
|---|---|---|---|
| `exception/ApiException.java` | Abstract base carrying an `HttpStatus` | "Expected" errors vs. real bugs | Everything becomes a 500 |
| `exception/{NotFound,Conflict,BadRequest,Unauthorized}Exception.java` | 404 / 409 / 400 / 401 | Named, intentful errors services throw | Ad-hoc status codes everywhere |
| `exception/GlobalExceptionHandler.java` | `@RestControllerAdvice`: maps exceptions + validation failures → the `ApiError` record; logs 500s | Every error has the same shape; stack traces don't leak | Inconsistent errors; leaked internals |

### Entry point
| File | What it is | Breaks if gone |
|---|---|---|
| `NowservingBackendApplication.java` | `@SpringBootApplication main()` — starts the app, component-scans `com.nowserving` | Nothing runs |

---

## 4. Non-code files

| File | What it is | Why it matters | Breaks if gone/wrong |
|---|---|---|---|
| `build.gradle` | Dependencies + build | Defines what libraries exist and the Java version | Won't compile/build |
| `settings.gradle` | Project name | Gradle needs it | Build fails |
| `application.yml` | Runtime config: DB URL (port **5433**), JPA, Flyway, JWT secret, rate limit — all `${ENV:default}` | Config-as-env-vars; dev defaults committed, real secrets injected | Won't connect to DB; secrets hardcoded |
| `db/migration/V1__init.sql` | Flyway migration — the **source of truth** for the schema | Versioned, repeatable schema across every environment | Schema drift; `ddl-auto: validate` fails startup |
| `docker-compose.yml` | Postgres 16, named volume, healthcheck, host port **5433** | One command to get a real DB locally | No local DB |
| `.gitignore` | Excludes `build/`, `.gradle/`, IDE files | Keeps junk + potential secrets out of git | Repo bloat; leaked local config |
| `README.md` | What it is + how to run + demo script | Onboarding | New dev is lost |

### What each Gradle starter pulls in

| Dependency in `build.gradle` | What it actually gives you |
|---|---|
| `spring-boot-starter-webmvc` | Embedded Tomcat, Spring MVC, Jackson (JSON). *(Boot 3.x called this `-web`.)* |
| `spring-boot-starter-data-jpa` | Hibernate (the ORM), Spring Data repositories, HikariCP connection pool |
| `spring-boot-starter-security` | The servlet filter chain, `BCryptPasswordEncoder`, auth plumbing |
| `spring-boot-starter-validation` | Hibernate Validator — makes `@Email/@NotBlank/@Size` enforce |
| `spring-boot-starter-flyway` + `flyway-database-postgresql` | Runs `V*.sql` migrations at startup; Postgres dialect |
| `postgresql` (runtime) | The JDBC driver — how Java speaks to Postgres over the wire |
| `jjwt-api/impl/jackson` | Create + verify JWTs (impl/jackson are runtime-only so your code can't couple to internals) |
| `lombok` | Compile-time getters/constructors (`@Getter`, `@RequiredArgsConstructor`) |
| `testcontainers:postgresql` + `junit-jupiter` | Spin up a **real** throwaway Postgres in Docker for tests |

### Generated vs. hand-written

- **Generated by start.spring.io / Gradle (don't hand-edit lightly):**
  `NowservingBackendApplication.java`, `gradlew`, `gradlew.bat`,
  `gradle/wrapper/*`, `settings.gradle`, `.gitignore`, `.gitattributes`, and the
  *initial* `build.gradle`.
- **Everything else you wrote** — all of `config/controller/dto/entity/exception/
  repository/security/service`, `V1__init.sql`, `application.yml`,
  `docker-compose.yml`, all tests, README, this doc. `build.gradle` was
  hand-extended (jjwt, Testcontainers, lint).

---

## 5. Frontend (NS-9 / NS-10) — `../frontend`

**Built and browser-verified (July 23).** React 19 + TypeScript + Vite; plain
CSS; runs on `localhost:5173`. Layers mirror the backend's split: an `api`
layer (the only code that talks HTTP), an `auth` layer (the doorman), pages,
and cross-cutting hooks.

| File | What it is | Why it exists | Breaks if gone/wrong |
|---|---|---|---|
| `vite.config.ts`, `tsconfig*.json`, `package.json`, `index.html` | Toolchain: dev server/build, TS config, deps, the single HTML page React mounts into | Standard Vite scaffold | Nothing builds/renders |
| `.env` (`VITE_API_URL`) | The backend's address, injected at build time | Prod points elsewhere without code changes; **never secrets** (ships to the browser) | Frontend calls the wrong host |
| `src/index.css` | The whole design system: CSS variables, near-black + amber "ticket-counter LED" aesthetic | One place to retune the look | Unstyled app |
| `src/api/client.ts` | Shared Axios instance + **request interceptor** (attaches `Bearer` JWT from localStorage) + 401 handler + `errorMessage()` | Client-side mirror of `JwtAuthenticationFilter`; one place for HTTP policy | Requests go out unauthenticated; every page reinvents error handling |
| `src/api/types.ts` | Hand-written TS twins of the backend DTOs | The API contract, typed | Silent shape mismatches |
| `src/api/endpoints.ts` | One typed function per backend endpoint | Pages never import axios directly | HTTP details smear across pages |
| `src/auth/AuthContext.tsx` | JWT in localStorage + `me` in React state; `login/logout` | Global "who am I" without prop drilling | No session persistence across refresh |
| `src/auth/ProtectedRoute.tsx` | Redirects logged-out users to /login | **UX, not security** — the real wall is SecurityConfig | Logged-out users see empty dashboards |
| `src/hooks/usePolling.ts` | Fetch now + every N ms, with cleanup | The Sprint-1 transport, now the fallback | Zombie intervals; stale pages |
| `src/realtime/useLiveTopic.ts` | STOMP-over-WebSocket subscription (@stomp/stompjs), auto-reconnect, `live` flag | Sprint 2's push transport | Back to polling-only |
| `src/pages/LoginPage/SignupPage.tsx` | Owner auth forms | NS-9 | No way in |
| `src/pages/DashboardPage.tsx` | List/create queues, waiting counts | NS-9 | No owner console |
| `src/pages/QueueDetailPage.tsx` | QR code (react-qr-code), live line, **Next** + no-show | NS-9; subscribes `/topic/queues/{id}` ping → refetch | No staff screen |
| `src/pages/JoinPage.tsx` | Mobile-first `/j/{joinToken}` form; redirects to existing ticket via localStorage | NS-10 | Customers can't join |
| `src/pages/TicketPage.tsx` | `/t/{entryToken}` — giant glowing position; push-fed, 30s-poll fallback | NS-10; subscribes `/topic/entries/{token}` | Customers can't watch their place |
| `src/App.tsx` / `src/main.tsx` | Route table / React root + providers | The app's information architecture in one glance | Nothing routes |

---

## 5b. Sprint 2 in full — how a live update actually travels

**The problem, in one picture.** A WebSocket is a long-lived connection pinned
to **one** server process. Run two instances behind a load balancer and:

```
   customer's phone ──── socket ────> instance A
   staff tablet     ──── HTTP  ────> instance B     (taps "Next")
```

Instance B updates the database — but it holds **no socket** to that phone.
Before Redis, the push simply vanished and the customer waited for their poll.

**The fix.** Nobody pushes directly. Everyone publishes to a shared bus; every
instance relays what it hears to its *own* sockets.

```
 B: commit to Postgres
  └─> publish envelope ──> [ Redis channel "nowserving:realtime" ]
                                   │ broadcast to ALL subscribers
                     ┌─────────────┴─────────────┐
                     ▼                           ▼
              instance A                    instance B
        relays to its sockets         relays to its sockets
                     │
                     ▼
            the phone updates
```

| File | What it is | Why it exists | Breaks if gone/wrong |
|---|---|---|---|
| `config/WebSocketConfig.java` | STOMP endpoint `/ws`, simple broker on `/topic`, origin-checked handshake | The transport that lets the server speak first | No realtime at all |
| `realtime/RealtimePublisher.java` | The **port** (interface): "push this to that destination" | Callers never care how many instances exist | Services would couple to Redis |
| `realtime/RedisRealtimePublisher.java` | Publishes a JSON envelope to one Redis channel | The "shout" half of the bus | Cross-instance pushes vanish |
| `realtime/RedisRealtimeSubscriber.java` | Runs on **every** instance; relays bus messages to its own sockets | The "listen" half | Messages published but never delivered |
| `realtime/RealtimeMessage.java` | Envelope `{destination, payloadJson}` | Payload stays opaque JSON — typed deserialisation off a bus is an RCE vector | Security risk |
| `config/RedisConfig.java` | Subscribes this instance to the channel; auto-reconnects | Without it nothing listens | Silent realtime outage |
| `service/QueueChangedEvent.java` | "Queue X changed" fact | Services stay pure domain logic | Tight coupling |
| `service/QueueEventsBroadcaster.java` | `@TransactionalEventListener` → publishes **after commit** | Nobody sees a position that got rolled back | Ghost updates |

**Three decisions worth being able to defend:**

1. **The publisher never sends locally.** Redis echoes to all subscribers
   *including us*, so there is exactly **one** delivery path whether you run
   one instance or fifty. The alternative — send locally *and* publish, then
   filter your own echo — needs instance-id bookkeeping and gives you two
   paths that can silently drift apart.
2. **A Redis outage is logged, never rethrown.** The DB has already committed,
   so the line is correct regardless; clients fall back to their 30-second
   poll. Rethrowing would turn "the live update was late" into "the staff
   request failed" — exactly the NFR the PRD warns about.
3. **Redis is used *only* as a bus.** The PRD also suggests a Redis sorted set
   for position (`ZRANK`). We deliberately have **not** done that: at MVP
   scale one `COUNT` query is fine, and caching position would introduce a
   cache-coherence problem we don't yet need. Add it when a real number says
   to, not because the doc mentions it.

**Verified end to end:** two instances (8080 + 8081) on one Postgres and one
Redis; the browser's socket on A, the advance issued to B — the page ticked
2 → 1 instantly. `WebSocketPushTest` pins it in CI with real containers.

---

## 5bb. Sprint 3 — notifications & measured estimates

**One event, two consumers.** Adding notifications changed **zero lines** in
`QueueService`. That is the return on Sprint 2's event design:

```
                              ┌──> QueueEventsBroadcaster ──> WebSocket   (Sprint 2)
QueueService ──> QueueChangedEvent ─┤
                              └──> NotificationListener ──> Web Push  (Sprint 3)
```

| File | What it is | Why it exists |
|---|---|---|
| `service/WaitEstimator.java` | Rolling **median** of measured service times, Redis-cached, with a cold-start fallback | One estimator, so pushed and polled numbers can't disagree |
| `entity/ServiceSample.java` + `V3` | One row per customer served | You can't compute a median from an average |
| `notification/NotificationChannel.java` | The **port** — "reach this customer" | SMS becomes a new adapter, not a refactor |
| `notification/WebPushChannel.java` | Real adapter: encrypts + sends, wrapped in the circuit breaker | Where the outside world is touched |
| `notification/NotificationDispatcher.java` | Decides *who* and *what*; `@Transactional` | Separate bean **on purpose** — a self-call would silently skip the transaction |
| `notification/NotificationListener.java` | `@Async` + `@TransactionalEventListener` | Never announce a change that rolled back; never block the staff request |
| `config/AsyncConfig.java` | A separate thread pool (**bulkhead**) | A slow provider can't eat the threads staff need |
| `config/ResilienceConfig.java` | The circuit breaker | Turns a slow failure into an instant one |

**Median, not mean — the one-line argument.** Services of 120, 120, 120, 120
and **3600** seconds: the mean says ~14 min per person, the median says 2.
One customer who went to find an ATM must not poison every estimate.
`WaitEstimateIntegrationTest` asserts exactly this.

**Three guards that make notifications safe to retry:**
1. `next_notified_at` — stamped once, so a repeated event can't buzz twice
   (**idempotency**, which is what lets delivery be *at-least-once*).
2. Unique `(entry_id, endpoint)` — a page reload doesn't register a device four times.
3. `EXPIRED` responses **delete** the subscription rather than retrying a dead endpoint forever.

**Why the app survives a dead push provider** — three independent defences:
- **Async** — the staff request returns before any notification is attempted.
- **Bulkhead** — notification work runs on its own small pool; the web threads are untouched.
- **Circuit breaker** — after 5 failures it OPENs and rejects in microseconds
  instead of hanging. Slow calls count as failures, because a 30-second hang
  holds a thread while an error frees it instantly.

---

## 5c. Google sign-in (FR-1)

| File | What it is | Why it exists |
|---|---|---|
| `security/GoogleIdTokenVerifier.java` | The **port**: "is this really Google's token, and whose?" | Lets tests inject a fake — no network, no flakiness, and (in Sprint 5) no bills |
| `security/GoogleOidcTokenVerifier.java` | Real adapter: checks signature (Google's JWKS), expiry, **audience** | Each check blocks a specific attack — see the table below |
| `support/FakeGoogleIdTokenVerifier.java` *(test)* | In-memory stand-in | Can conjure an "unverified email" account on demand |
| `db/migration/V2__google_oauth.sql` | `password_hash` nullable, `google_sub` UNIQUE | Google-only owners genuinely have no password |

| Check | Attack it stops |
|---|---|
| Signature vs. Google's public keys | Hand-written token claiming to be you |
| Expiry | An old token, e.g. scraped from a log, working forever |
| **Audience = our client id** | A token minted for *another* app replayed here ("confused deputy") |

**The subtle one — account linking.** If someone signed up with a password and
later clicks the Google button with the same email, we attach Google to the
existing account rather than creating a second one. That only happens when
Google reports `email_verified`. Without that guard it is an account-takeover
hole. Setup steps and the flow trade-off live in
[GOOGLE_OAUTH_SETUP.md](GOOGLE_OAUTH_SETUP.md).

> The "← NOT BUILT" markers in the traces below are now historical: the React
> layer exists exactly where those markers sit.

---

## 5d. V9 — Discovery, the role split, and the door policy

Three problems were solved together, because they were the same problem.

### The role-design bug this fixed

`/` was the owner dashboard. A logged-out visitor was redirected to a page
headed **"Owner console"** — and there is only one account type in the whole
schema (`Owner`), so one tap on its Google button ran
`AuthService.loginWithGoogle` **case 3**, which creates a `Business` + `Owner`
and names the business `"<their name>'s Business"`. A customer who typed the
domain instead of scanning a QR code silently became a restaurant.

The fix is information architecture, not code:

```
CUSTOMER (no account, ever)   /   /v/:t   /j/:t   /t/:t   /b/:t   /r/:t
OWNER    (JWT)                /owner/**
```

which now mirrors the backend's own `/public/**` vs JWT-walled split. Old
owner URLs redirect, so existing bookmarks survive.

### One token, two doors

The single most important property of discovery is that it added **no second
way to join**:

```
Restaurant → active queue → joinToken → { QR code, "Join Waitlist" button }
```

`/public/venues` publishes the same `joinToken` the poster already encodes, so
publishing it reveals nothing new. Everything downstream — position,
estimates, Leave-Now, notifications — never learns which door was used. The
one place the door matters is a single boolean, `viaQr`.

### The door policy (owner-controlled, V9 columns on `queues`)

| Column | Question it answers |
|---|---|
| `listed_publicly` | Do I appear in public search? |
| `allow_remote_join` | Can someone join without being here? |
| `max_remote_join_miles` | ...and from how far? (1–50, **50 is the ceiling, not the setting**) |
| `allow_qr_join` | Is the QR door open? |

Hard-coding one radius for every restaurant was the thing worth avoiding: a
three-table cafe turning over every ten minutes and a city restaurant with a
90-minute line have genuinely different answers, so the radius is *policy*,
set per venue.

`PublicQueueService.enforceJoinPolicy` resolves it, and its three outcomes are
each a different HTTP status **on purpose**:

```
QR scan            → allowed (presence is self-evident)   201
remote, off        → refused, "scan the QR at the door"   400
remote, on, no loc → "share your location to confirm"     428  ← ask & retry
remote, on, too far→ refused, WITH the actual numbers     400
no venue coords    → allowed (degrade toward working)     201
```

**428 Precondition Required is load-bearing.** It is what lets the client tell
"go ask the browser for permission and retry" apart from "you are refused" —
without string-matching on error copy, which breaks the moment someone
improves the wording. `DiscoveryAndRemoteJoinTest` asserts that distinction
explicitly, because a regression to 400 would silently kill remote joining.

Two honest limitations, documented rather than hidden:

- `viaQr` is a client-supplied flag and can be forged. Accepted: the threat
  model is "stop a well-meaning customer 45 miles away from holding a table",
  not an adversary — and the cost of being wrong is one no-show, which the
  FR-16 grace/bump policy already handles. Real proof of presence needs venue
  wifi or a rotating code.
- `listed_publicly` defaults to **TRUE** so the demo isn't empty. For a real
  multi-tenant launch the safe default is FALSE plus a one-time opt-in.

### Sequencing: ask *after* the join, not before

The customer flow deliberately asks for nothing but a name and a party size:

```
find restaurant → see wait → join → ticket → optionally enable Leave-Now
```

A permission prompt shown before someone has anything to lose is a prompt they
decline, and a declined geolocation permission is sticky. So location is
requested **on the ticket page**, once the value is concrete ("want us to tell
you when to leave?"), and the notification channel is revised there too via
`PATCH /public/entries/{t}/notify-preference`. The only exception is the
remote-eligibility check, and even that is demand-driven — the client doesn't
touch geolocation until a 428 says it's required.

### Notifications (the 5th change)

`NotifyChannel` (PUSH | SMS | NONE) is a customer *preference*; delivery lives
behind two ports. SMS became `SmsSender` rather than a second
`NotificationChannel` implementation because that port's `send()` takes a
`PushSubscription` — a browser endpoint plus two keys — and SMS needs a phone
number. Two small honest ports beat one dishonest one; they share
`PushMessage`, which is the part that genuinely is the same.

Routing lives in `NotificationDispatcher`, not in any channel: a channel
delivers, it doesn't decide whether it should have been asked. That is why SMS
arrived as two new files plus one `switch`, with the Leave-Now engine, the
circuit breaker and every caller of `notifyCustom()` untouched. `TwilioSmsSender`
is off by default and answers `NOT_CONFIGURED`, falling back to Web Push —
the same off-by-default-and-degrade pattern as Google sign-in, Web Push and
TomTom. Email is deliberately absent for urgent alerts: a "leave now" that
lands in a promotions tab twenty minutes late is worse than none, because the
customer trusted it.

---

## 6. End-to-end traces (Step 3)

These traces were written before the React layer existed and show the **real
backend path**, marking where the frontend sits. The "← NOT BUILT" markers are
historical — that layer exists now.

### Trace A — Owner signs up, then logs in

```
[React: would POST from a Login form]        ← NOT BUILT
      │  POST /auth/signup {businessName,email,password,displayName}
      ▼
RateLimitFilter        → path not /public/** → shouldNotFilter=true → skip
      ▼
JwtAuthenticationFilter→ no Bearer header → do nothing, pass through
      ▼
SecurityConfig         → /auth/** is permitAll → allowed
      ▼
AuthController.signup   @Valid triggers Bean Validation (400 if bad)
      ▼
AuthService.signup      @Transactional  ← one unit of work
      │   existsByEmail? → 409 ConflictException if taken
      │   BusinessRepository.save(new Business)      INSERT businesses
      │   passwordEncoder.encode(password)           BCrypt (slow on purpose)
      │   OwnerRepository.save(new Owner)            INSERT owners
      │   (DataIntegrityViolation on race → 409)
      ▼  commit — both rows or neither
   SignupResponse → 201 JSON  (no passwordHash — it's a DTO)

--- then login ---
      │  POST /auth/login {email,password}
      ▼  (same filters; /auth/** public)
AuthController.login → AuthService.login  @Transactional(readOnly)
      │   OwnerRepository.findByEmail → 401 "Invalid email or password" if absent
      │   passwordEncoder.matches?     → same 401 message if wrong (no enumeration)
      │   JwtService.issue(AuthenticatedOwner)  ← signs {sub, businessId, email}
      ▼
   LoginResponse {token, expiresAt} → 200
      │
[React: would store token]                    ← NOT BUILT
```

### Trace B — Customer scans the join link and joins

```
[Customer opens /j/{joinToken}; React form would POST]   ← NOT BUILT
      │  POST /public/queues/{joinToken}/entries {customerName,partySize}
      ▼
RateLimitFilter        → /public/** → count this IP; >30/min → 429 and STOP
      ▼
JwtAuthenticationFilter→ no token → pass (customers have no account)
      ▼
SecurityConfig         → /public/** permitAll
      ▼
PublicQueueController.join
      ▼
PublicQueueService.join  @Transactional
      │   QueueRepository.findByJoinToken → 404 if unknown
      │   status == CLOSED? → 400 BadRequestException
      │   QueueEntryRepository.save(new QueueEntry)   INSERT queue_entries
      │        (@PrePersist stamps joined_at + generates entry_token)
      │   countWaitingAhead(queueId, joinedAt, id)    SELECT COUNT(*)
      │   position = ahead + 1;  estimate = ceil(ahead*svcMin/stations)
      ▼
   JoinResponse {entryToken, queueName, position, peopleAhead, estimatedMinutes} → 201
      │
[React: would save entryToken to localStorage]  ← NOT BUILT
```

### Trace C — Staff taps "Next"; customer's position updates

```
[Staff dashboard: React would POST]           ← NOT BUILT
      │  POST /queues/{id}/advance   Authorization: Bearer <jwt>
      ▼
RateLimitFilter        → not /public → skip
      ▼
JwtAuthenticationFilter→ validate token → SecurityContext = AuthenticatedOwner
      ▼
SecurityConfig         → anyRequest authenticated → allowed (401 if token bad)
      ▼
QueueController.advance(@AuthenticationPrincipal principal, id)
      │   passes principal.businessId()  ← from the TOKEN, never the URL/body
      ▼
QueueService.advance(businessId, queueId)  @Transactional
      │   requireOwnedQueue → findByIdAndBusinessId → 404 if not yours
      │   lockNextWaiting(queueId)   SELECT … FOR UPDATE SKIP LOCKED LIMIT 1
      │        → BadRequestException "No one is waiting" if empty (400 not 500)
      │   front.setStatus(SERVED); setServedAt(now)   ← dirty-checked UPDATE
      │   findFirstBy… WAITING → the new front (nextUp)
      ▼  commit — row lock releases here
   AdvanceResponse {served, nextUp} → 200

--- customer sees the change on their NEXT poll ---
      │  GET /public/entries/{entryToken}
      ▼
PublicQueueService.position → countWaitingAhead → position is now 1 lower
      ▼
   PositionResponse {status, position, peopleAhead, estimatedMinutes} → 200
      │
[React: would poll every 10s and re-render]   ← NOT BUILT
```

**Key insight the traces expose:** the customer's position "moving up" is not
pushed — it's **recomputed on every read**. Nothing stores position, so nothing
can go stale. Sprint 2's WebSockets change *how the customer finds out* (push vs.
poll), not *how position is computed*.
