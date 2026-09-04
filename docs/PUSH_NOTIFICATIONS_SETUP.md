# Web Push (VAPID) setup — FR-8

Push notifications need one key pair. It costs nothing, needs no account, and
takes about a minute.

---

## Generate the keys

```bash
npx web-push generate-vapid-keys
```

Output looks like:

```
Public Key:
BEXAMPLEpublicKEYreplaceThisWithYourOwn_generatedByWebPush_0123456789abcdefGHIJKLMNOPqrs

Private Key:
EXAMPLEprivateKEYreplaceThisWithYourOwn
```

**This is the correct tool for this backend.** The keys it produces are RAW
base64url, which is the format `VapidKeys.fromUncompressedBytes(...)` expects —
and the format every web-push implementation in every language uses. The same
pair works here and in any JS tooling.

---

## Wire them in

**1. Public key → `config/application.properties`** (gitignored, but a public
key is not a secret — it ships to every browser inside the subscription):

```properties
app.push.vapid.public-key=BEXAMPLEpublicKEYreplaceThisWithYourOwn_generatedByWebPush_0123456789abcdefGHIJKLMNOPqrs
app.push.vapid.private-key=${VAPID_PRIVATE_KEY:}
```

**2. Private key → an environment variable.** Never a committed file — same
rule as `JWT_SECRET` and the database password:

```bash
export VAPID_PRIVATE_KEY='EXAMPLEprivateKEYreplaceThisWithYourOwn'
```

Then start the app in that shell:

```bash
./gradlew bootRun
```

**3. Confirm it worked.** On startup you should see:

```
INFO  c.n.notification.WebPushChannel : Web Push enabled (VAPID public key BFWAnx89fUu4…)
```

If instead you see *"VAPID keys are present but unusable"*, read the format
note below — the message names the likely cause.

---

## Key formats — the trap this project already fell into

The `com.interaso:webpush` library accepts two different encodings through two
different methods, and choosing wrong gives a misleading error
(`InvalidKeySpecException: not enough content`, which reads like a corrupt key
rather than a wrong wrapper).

| | Raw base64url — **what we use** | X.509 / PKCS#8 |
|---|---|---|
| Library method | `VapidKeys.fromUncompressedBytes(pub, priv)` | `VapidKeys.create(pub, priv)` |
| Public key looks like | `BFWAnx89…` (87 chars, starts `B`) | `MFkwEwYHKoZIzj0…` (starts `MFkwEw`) |
| Private key looks like | 43 chars | much longer |
| Produced by | `npx web-push generate-vapid-keys` | `VapidKeys.generate().getX509PublicKey()` |
| Portable across languages | ✅ | ❌ Java-specific packaging |

We deliberately standardised on **raw**, because it is what the whole web-push
ecosystem speaks.

---

## Rotating keys

Changing the key pair **invalidates every existing browser subscription** —
devices subscribed with the old `applicationServerKey` will stop receiving
notifications and must opt in again. Only rotate if the private key leaks.

The frontend never hard-codes the key: it fetches it from
`GET /public/push/vapid-key`, so a rotation needs no frontend change.

---

## Testing without any of this

`./gradlew test` never touches Web Push. `FakeNotificationChannel` replaces the
real one in every test, so the suite is fast, deterministic, and works offline.
