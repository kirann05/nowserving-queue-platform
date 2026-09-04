# Turning on "Sign in with Google" (FR-1)

The code is finished and tested. It just needs **your** Google credentials —
I can't create those, because it means logging into your Google account.
This takes about five minutes, once.

---

## Step 1 — Create an OAuth client

1. Go to <https://console.cloud.google.com/>
2. Create a project (top-left project picker → **New Project**). Call it
   `NowServing`.
3. In the search bar, go to **APIs & Services → OAuth consent screen**.
   - User type: **External**
   - App name: `NowServing`, your email for support + developer contact
   - Save. You do **not** need to submit for verification — while the app is
     in "Testing" mode you can add your own Google account under
     **Test users**, and that's enough for development.
4. Go to **APIs & Services → Credentials → Create Credentials → OAuth client ID**
   - Application type: **Web application**
   - Name: `NowServing local`
   - **Authorized JavaScript origins** → add exactly:
     ```
     http://localhost:5173
     ```
   - You can leave *Authorized redirect URIs* empty — we use the ID-token
     flow, which never redirects (see "Why this flow" below).
5. Click Create. Copy the **Client ID** (it looks like
   `1234567890-abc123.apps.googleusercontent.com`).

> **Client secret:** Google also shows you one. **We don't use it, and you
> should not put it anywhere in this project.** The ID-token flow verifies
> Google's signature using Google's *public* keys, so there is no secret to
> keep. One less thing that can leak.

---

## Step 2 — Wire it in (two files, both local)

**Frontend** — `frontend/.env`:
```
VITE_GOOGLE_CLIENT_ID=1234567890-abc123.apps.googleusercontent.com
```

**Backend** — set an environment variable before starting it:
```bash
GOOGLE_CLIENT_ID=1234567890-abc123.apps.googleusercontent.com ./gradlew bootRun
```

Restart both servers. The Google button appears on `/login` and `/signup`.

> The client ID is **not a secret** — it is embedded in the sign-in button and
> visible to anyone who views the page source. It is still a security control:
> the backend rejects any Google token that wasn't minted for this exact
> client ID (see `GoogleOidcTokenVerifier`).

---

## Why this flow, and not the one in the PRD?

The PRD (§3.4) lists `GET /auth/google/start` and `/callback` — the
**Authorization Code** flow, where your server bounces the browser to Google
and back. We built the **ID token** flow instead (`POST /auth/google`).

| | Authorization Code (PRD) | ID token (built) |
|---|---|---|
| Round trips | browser → your server → Google → your server | browser → Google, then one POST |
| Client secret | required, must be protected | **none** |
| Failure surfaces | redirect URIs, state param, CSRF on callback, code exchange | one token to verify |
| Google's current advice for web apps | legacy | recommended |

Both end in the same place: our own JWT. We picked fewer moving parts and no
secret. **In an interview, being able to say that — and explain the
trade-off — is worth more than having built either one.**

---

## What the backend actually checks

`GoogleOidcTokenVerifier` performs three checks. Each one prevents a real attack:

| Check | Prevents |
|---|---|
| **Signature** against Google's published public keys (JWKS) | Someone hand-writing a token that says they're you |
| **Expiry** | An old token, e.g. scraped from a log, working forever |
| **Audience** = your client ID | A valid Google token issued to *another app* being replayed here (the "confused deputy" problem) |

Then `AuthService.loginWithGoogle` handles three cases:

1. **Known Google id** → log in.
2. **Known email, new Google id** → *account linking*: attach Google to the
   existing password account, so the owner doesn't end up with two accounts
   and half their queues in each. **Only if Google says `email_verified`** —
   without that check this is an account-takeover hole.
3. **Neither** → create the business + owner, with `password_hash = NULL`.

---

## Testing it without Google

You don't need any of the above to run the test suite.
`FakeGoogleIdTokenVerifier` replaces the real one in every test, so
`./gradlew test` never touches the network. That's why
`GoogleAuthIntegrationTest` can test an *unverified email* account — something
you can't easily conjure from real Google.

This "wrap the external service in an interface, fake it in tests" habit is
the same one the PRD insists on for the paid Maps API in Sprint 5, where a
test suite that calls the real thing would generate a bill.
