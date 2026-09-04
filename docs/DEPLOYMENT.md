# Deploying NowServing

Everything here is ready to run. Pick **one** of the three targets below.

> **Read this first — three browser rules that shape every option:**
> Web Push, the Geolocation API and Google sign-in **all require HTTPS**
> (`localhost` is the only exception). So a live demo needs a real domain with
> a real certificate. Every option below gives you that.

---

## 0. Generate your secrets (5 minutes, do this once)

```bash
# JWT signing key — 32+ bytes. Never reuse the dev default.
openssl rand -base64 48
```

```bash
# VAPID keys for Web Push. RAW base64url is the format this app expects.
npx web-push generate-vapid-keys --json
```

Optional, free, no credit card — live traffic for the Leave-Now engine:
sign up at <https://developer.tomtom.com/> and create an API key. Without it
the app uses free straight-line estimates and everything still works.

Now create `.env` **next to `docker-compose.prod.yml`**:

```bash
DB_PASSWORD=<openssl rand -base64 24>
JWT_SECRET=<the openssl output above>
PUBLIC_BASE_URL=https://queue.example.com
PUBLIC_API_URL=https://queue.example.com/api
GOOGLE_CLIENT_ID=<your client id, or leave empty>
VAPID_PUBLIC_KEY=<publicKey from web-push>
VAPID_PRIVATE_KEY=<privateKey from web-push>
TOMTOM_API_KEY=<optional>
```

> `.env` must never be committed. `/config/` and `.env` are already gitignored.

---

## Option A — Single VM + Docker Compose (recommended, ~$5/mo)

The PRD's own recommendation (§6.3). One box, one command, full control, and
the ops skills transfer everywhere.

**1. Get a server.** Hetzner CX22 (~€4) or DigitalOcean basic ($6). Ubuntu 24.04.

**2. Point your domain at it.** Create an `A` record for `queue.example.com`
→ your server's IP. **Do this before step 5** — Caddy proves domain ownership
over HTTP and cannot get a certificate for a domain that doesn't resolve yet.

**3. Install Docker:**
```bash
curl -fsSL https://get.docker.com | sh
```

**4. Copy both repos to the server** (they must sit side by side, because the
compose file builds the frontend from `../frontend`):
```bash
scp -r backend frontend root@YOUR_IP:/opt/
```

**5. Set your domain in `Caddyfile`** (replace `queue.example.com` and the
email), then:
```bash
cd /opt/nowserving/backend && docker compose -f docker-compose.prod.yml up -d --build
```

**6. Watch it come up:**
```bash
docker compose -f docker-compose.prod.yml logs -f backend
```

Visit `https://queue.example.com`. Caddy fetches a certificate on first
request — the first load takes a few seconds.

---

## Option B — Railway (fastest, ~10 minutes)

1. Push both repos to GitHub.
2. <https://railway.app> → **New Project → Deploy from GitHub** → pick the backend.
3. **+ New → Database → PostgreSQL**, then again for **Redis**.
4. In the backend service's **Variables**, add everything from your `.env`,
   plus wire the managed services:
   - `DB_URL` = `jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}`
   - `DB_USERNAME` = `${{Postgres.PGUSER}}`, `DB_PASSWORD` = `${{Postgres.PGPASSWORD}}`
   - `REDIS_HOST` = `${{Redis.REDISHOST}}`, `REDIS_PORT` = `${{Redis.REDISPORT}}`
   - `SPRING_PROFILES_ACTIVE` = `prod`
5. Deploy the frontend as a second service with build args `VITE_API_URL` and
   `VITE_GOOGLE_CLIENT_ID`.
6. Railway gives each service an HTTPS domain. Put the **frontend's** domain in
   `PUBLIC_BASE_URL` / `CORS_ALLOWED_ORIGINS` and redeploy the backend.

---

## Option C — Kubernetes (learning, not hosting)

Manifests are in `k8s/`. For a local cluster:

```bash
brew install minikube && minikube start
```
```bash
eval $(minikube docker-env) && docker build -t nowserving-backend:latest .
```
```bash
kubectl apply -f k8s/ && kubectl get pods -w
```

This proves the manifests and the multi-replica Redis fan-out. It is **not** a
public demo — for that use A or B.

---

## After deploying — three things that WILL bite you

**1. Google sign-in will fail until you add the new origin.**
Cloud Console → Credentials → your OAuth client → **Authorized JavaScript
origins** → add `https://queue.example.com`. Google warns changes take
"five minutes to a few hours". Your `localhost:5173` entry can stay.

**2. Push subscriptions are tied to the VAPID key AND the origin.**
Change either and existing subscriptions are dead — customers must re-opt-in.
Fine for a fresh demo; know it before you rotate keys later.

**3. Set a hard spend cap on TomTom** in their console. The app has a monthly
budget guard (`MAPS_MONTHLY_BUDGET`, default 2000 calls) which degrades to free
estimates when exhausted — but a provider-side cap is the backstop that
doesn't depend on our code being right.

---

## Verify the deploy

```bash
curl https://queue.example.com/api/health
```
Expect `{"status":"UP","dbConnected":true,...}`.

Then walk the demo: sign up → create a queue → set opening hours and venue
coordinates → open the join link on a phone → advance from the dashboard and
watch the phone update instantly.

---

## Operating it

| Task | Command |
|---|---|
| Logs | `docker compose -f docker-compose.prod.yml logs -f backend` |
| Restart | `docker compose -f docker-compose.prod.yml restart backend` |
| Update | `git pull && docker compose -f docker-compose.prod.yml up -d --build` |
| **Back up the DB** | `docker compose -f docker-compose.prod.yml exec postgres pg_dump -U nowserving nowserving > backup-$(date +%F).sql` |
| Metrics | `curl -u … https://queue.example.com/api/actuator/prometheus` |

**Take a backup before every update, and restore one at least once.** An
untested backup is not a backup.

---

## What is still NOT production-grade

Being straight about this matters more than the deploy itself:

| Gap | Consequence |
|---|---|
| **No load test** | The PRD's `<1s p95` SLI is unproven at scale |
| **No E2E browser test** | Flows are verified by hand, not in CI |
| **No monitoring dashboard or alerts** | Metrics are exposed; nobody is watching them |
| **No automated backups** | The command above is manual |
| **JWT can't be revoked** | A leaked token is valid for 24h |
| **Single instance of everything** | No redundancy; a restart is downtime |
| **Rate limiter is per-instance** | Weaker than it looks once you scale out |

For a portfolio demo this is fine, and saying so precisely is far stronger
than claiming "production ready" and being asked what your p95 is.
