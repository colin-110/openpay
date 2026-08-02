# Security Audit

A read of the whole codebase looking for ways to move money you should not be able to move, read
data that is not yours, or reach something that should not be reachable.

Every finding below was confirmed by reading the code, not inferred. Each says where it is, what it
actually lets someone do, and what fixing it takes.

| # | Finding | Severity | Status |
| --- | --- | --- | --- |
| 1 | Session roles are never enforced | **High** | Fixed |
| 2 | API key scopes are never enforced | **High** | Fixed |
| 3 | provider-router-service has no authentication at all | **High** | Fixed |
| 4 | SSRF through the merchant webhook URL | **Medium** | Fixed |
| 5 | No replay window on inbound acquirer callbacks | **Medium** | Fixed |
| 6 | One admin token opens everything | **Medium** | Fixed |
| 7 | No rate limiting on merchant-facing writes | **Medium** | Fixed |
| 8 | notification-service holds the platform admin token | **Medium** | Fixed |
| 9 | Login limiter is in-memory and keyed only on email | **Low** | Fixed |
| 10 | JWT issuer and audience are not validated | **Low** | Fixed |
| 11 | CORS ships a development origin as its default | **Low** | Fixed |
| 12 | No TLS anywhere | **Info** | Accepted |
| 13 | Outdated dependencies carrying known CVEs | **Critical** | Fixed (1 accepted) |

The surfaces added since that audit — the fraud gate and its rules, the routing table, dead-letter
replay, and the audit log — were reviewed on the same terms as they were built. What that review
concluded is at the end, under [Surfaces added since](#surfaces-added-since).

---

## 1. Session roles are never enforced — High

**Where.** `JwtIssuer` puts `role` in every token. `JwtAuthenticationFilter` reads it into
`ApiKeyPrincipal`. Then nothing, anywhere, reads it:

```
$ grep -rn "\.role()" --include=*.java services libs | grep -v /test/
services/auth-service/.../CreateUserRequest.java:16:   @Pattern(regexp = "MERCHANT_ADMIN|MERCHANT_VIEWER")
services/auth-service/.../UserService.java:61:         request.role()
```

Both hits are at *user creation*. The role is validated when the account is made and never consulted
again.

**What it lets someone do.** A `MERCHANT_VIEWER` — an account whose entire purpose is read-only
access — can `POST /api/v1/refunds` and send money out of the business. Nothing distinguishes it
from an admin at request time.

**Fix.** Enforce the authority at the point of the action — see `ApiKeyPrincipal.requireWrite`,
called by the two endpoints that move money.

---

## 2. API key scopes are never enforced — High

**Where.** Same root cause. `api_keys.scope` is stored, returned by `validate-key`, and carried in
the principal. It is never checked.

**What it lets someone do.** A key issued as `payments:read` — the sort of key you would hand to a
reporting tool, an analytics vendor, or a contractor — can create payments and issue refunds. The
scope is decoration.

**Fix.** Same mechanism as roles: the credential's authority is checked before the action, whether
that authority came from a key's scope or a session's role.

---

## 3. provider-router-service has no authentication at all — High

**Where.** Every other service configures `admin-paths` or `api-key-paths`. The router configures
neither, and `RouterAdminController` maps `/internal/router/**` with no filter in front of it. Port
8085 is published in `docker-compose.apps.yml`.

It is worse than a missing configuration block. Adding one had no effect, and the reason took a
live test to find: **provider-router-service does not have `common-security` on its classpath at
all.** No dependency, so no auto-configuration, so no filters — configuration alone was inert. The
same is true of webhook-service, though there the HMAC signature *is* the authentication, so it is
covered.

**What it lets someone do.** Anyone who can reach the port can call

```
GET /internal/router/payments/{anyPaymentId}/attempts
GET /internal/router/providers
```

No merchant scoping, no credential. `provider_transactions` carries `merchant_id` but the query
ignores it, so this is a cross-tenant read: given a payment id, you learn which acquirer took it,
the provider's own reference, and why attempts failed. `/providers` additionally exposes live
circuit-breaker state, which tells an attacker exactly when the platform is degraded.

"Internal" was doing all the work here, and internal is a description of intent, not a control.

**Fix.** Add the dependency, then guard the paths — with a **service token, not the admin token**,
so payment-service can read attempt history without holding the credential that opens onboarding
and the ledger. The attempt query now also requires the merchant id, so a leaked payment id on its
own reads nothing.

---

## 4. SSRF through the merchant webhook URL — Medium

**Where.** `CreateMerchantRequest.webhookUrl` is validated as `@Size(max = 512)` and nothing else.
`WebhookDispatcher` builds a client with **no base URL** and calls it directly:

```java
this.restClient = RestClient.builder().requestFactory(factory).build();
...
.uri(config.webhookUrl())
```

**What it lets someone do.** Whatever string is in that column, the platform will POST to — from
inside the network, with a valid HMAC signature attached. That includes
`http://169.254.169.254/latest/meta-data/iam/security-credentials/` on EC2,
`http://metadata.google.internal/`, `http://localhost:8086/api/v1/ledger/...`, or any other service
that is not meant to be reachable from outside.

Setting the URL is admin-gated today, which limits *who* can aim it — but that is blast radius, not
a control. The moment merchants configure their own webhook URL, as they do on every real gateway,
this becomes directly exploitable.

**Fixed, in two places, because one was never going to be enough.**

**Where the URL is stored**, `OutboundUrlPolicy.requireDeliverable` requires `https` (with `http`
allowed only for loopback in development), refuses credentials in the URL, and refuses loopback,
link-local, private, multicast and wildcard addresses. A host that does not resolve is deliberately
allowed: DNS is transient, and refusing a merchant's URL because its domain was briefly
unresolvable would block a legitimate setup for a reason unrelated to the URL.

**Where the connection is made**, `PublicAddressDnsResolver` applies the same address rule inside
DNS resolution. This is the half that actually closes the hole. Checking the URL and then
connecting leaves a window — the HTTP client resolves the name again when it opens the socket, so
an attacker controlling the record answers publicly for the check and with `169.254.169.254` for
the connection. That is DNS rebinding, and no amount of checking beforehand prevents it. Resolving
through the policy removes the window, because the addresses it returns are the addresses connected
to; there is no second lookup to poison.

**Redirects are disabled.** This turned out to be the easier attack and it was wide open:
`WebhookDispatcher` used `HttpURLConnection`, which follows redirects by default, so a merchant
endpoint replying `302 Location: http://169.254.169.254/` would have had the platform fetch it. A
webhook endpoint has no business redirecting us.

Both were verified against the running stack with a stand-in merchant on loopback. A well-behaved
endpoint received its webhook (`DELIVERED`, one attempt, 200). An endpoint replying `302` towards a
third port was retried three times and **the redirect target was never contacted**. Repointing a
stored URL at `10.0.0.5` directly in the database — which is what a rebind amounts to — produced
`10.0.0.5 resolves to 10.0.0.5, which is not publicly routable` and no connection.

The policy lives in `common-security` and is used by both services, so the two checks cannot drift
apart.

---

## 5. No replay window on inbound acquirer callbacks — Medium

**Where.** `WebhookController` takes `X-Provider-Signature` and a body. There is no timestamp header
and no freshness check. The only replay defence is the unique constraint on
`(provider, provider_event_id)`.

**What it lets someone do.** Anyone who captures one signed callback holds a message that stays
valid forever. Today the dedup table blocks the replay — but that table has no retention policy, so
the protection depends on rows never being deleted. Prune it for size, as you eventually will, and
every pruned event becomes replayable. A replayed capture callback moves a payment to `CAPTURED`.

**Fixed.** The signature now covers `timestamp.body` rather than `body`, and a
`X-Provider-Timestamp` header is required. A callback more than five minutes from our clock is
refused with `stale_timestamp`, and the tolerance is configurable.

Three details matter more than the header itself:

- **The timestamp is inside the signature.** If it travelled alongside, an attacker would replay a
  captured message with today's timestamp and the freshness check would wave it through. There is
  an acceptance check for exactly that.
- **Missing is not "skip".** A callback with no timestamp is refused rather than falling back to
  the old behaviour, or the check is opt-out by deleting a header.
- **Freshness is checked after the signature.** An unauthenticated caller probing timestamps learns
  nothing about our clock or our tolerance.

Skew is tolerated in both directions. A provider whose clock runs a little fast is not replaying
anything, and refusing it would turn a clock difference into payments that never capture.

The deduplication table still does its job for genuine retries. What changed is that it is no
longer the only thing standing between a captured callback and a replayed capture, so pruning it
for size is now a storage decision rather than a security one.

Verified against the running stack: a callback signed correctly with an hour-old timestamp is
refused, the same signature replayed with a fresh timestamp is refused, one with no timestamp is
refused, and one signed now is accepted — while ordinary payments still reach `CAPTURED` on their
own, which is the check that proves both sides of the scheme agree.

---

## 6. One admin token opens everything — Medium

`OPENPAY_ADMIN_TOKEN` is a single shared secret that authorises merchant onboarding, API key
issuance, dashboard user creation, the general ledger, settlements and payouts, webhook delivery
history, and merchant signing secrets. Five services compare against the same string. There is no
separation of duties and no way to grant read-only operator access, and rotating it means rotating
it everywhere at once.

**Fixed.** Split into three tiers, each a separate secret, so leaking one does not leak the others:

| Header | Authorises | Why it is its own tier |
| --- | --- | --- |
| `X-Admin-Token` | Onboarding a merchant, issuing an API key, rotating a webhook secret, creating a dashboard user | Anything that creates a business identity, or a credential capable of moving money on its own |
| `X-Ops-Token` | The general ledger, closing a settlement window, cross-merchant delivery history | Reporting and administration that mints nothing. The one tier safe to embed in a dashboard or a cron job |
| `X-Internal-Token` | Router attempt history, merchant webhook config | Service-to-service. The narrowest tier: one service reading one thing from a peer |

The dividing line is *does this create a credential*, not *is this sensitive*. The ledger is highly
sensitive and sits on the ops tier anyway, because reading it does not let you mint anything —
whereas a token that can issue an API key goes on to do everything that key can do, indefinitely.

Verified live: the admin token is now **refused** on the ledger, on `/internal/settlements/run`, on
cross-merchant delivery history, and on merchant webhook config. Every one of those was reachable
with it before.

Still one shared secret per tier rather than a real operator identity with per-person permissions.
That is the honest limit of this fix: it cuts the blast radius of a leak substantially and makes
rotation independent per tier, but it does not tell you *which* operator did something.

---

## 7. No rate limiting on merchant-facing writes — Medium

`ValidationAttemptLimiter` throttles failed key validations and failed logins. Nothing throttles
*successful* authenticated traffic. A merchant with a valid key can create payments as fast as it can
open connections, and each one fans out to Kafka, the router, an acquirer, the ledger, settlement,
and an outbound webhook. One misbehaving integration is a platform-wide incident.

**Fixed.** A per-merchant fixed-window counter at the gateway, in Redis so the limit holds across
replicas — an in-memory counter would mean N replicas each allowing the full quota independently.

Three deliberate choices:

- **It runs after authentication.** An invalid credential is rejected before this filter sees it,
  so the limiter counts real merchant traffic rather than someone guessing at API keys.
- **Writes only.** A read costs far less than a write, which is what fans out to Kafka, the router,
  an acquirer, the ledger, settlement and a webhook. Limiting reads too would not track the risk.
- **It fails open.** A rate limiter is an availability protection, not a security invariant like
  authentication. Refusing every request because Redis blipped would make the limiter a bigger
  outage than the abuse it exists to prevent.

Fixed window rather than sliding or a token bucket: one `INCR` plus one `EXPIRE`, correct under
concurrent callers with no extra coordination. It is not exact — a caller can get up to double the
nominal limit across a window boundary — and that imprecision is worth it for something that runs
on every request.

Verified live with a 60-request concurrent burst against a 30-per-5s limit: exactly 30 succeeded
and 30 returned `429` with `Retry-After: 5`, while reads from the same throttled merchant kept
returning `200`.

---

## 8. notification-service holds the platform admin token — Medium

It needs merchant signing secrets, and the only way to read them is
`GET /api/v1/merchants/{id}/webhook-config`, which is admin-gated. So the service that makes
outbound connections to arbitrary third-party URLs — the one most exposed to the outside world — is
also the one holding the credential that opens the ledger and issues API keys.

**Fixed.** The endpoint moved to `GET /internal/merchants/{id}/webhook-config`, guarded by
`X-Internal-Token`, and notification-service no longer holds the admin token at all. The
admin-gated `/api/v1/merchants/{id}/webhook-config` was removed rather than kept alongside —
leaving it would have meant the admin token still reached every merchant's live signing secret,
which is the thing this finding was about.

Verified live: the old path returns `404`, the new one returns `401` for both no credential and the
admin token, and `200` only for the service token — while webhook delivery still completes on the
first attempt.

---

## 9. Login limiter is in-memory and keyed only on email — Low

`ValidationAttemptLimiter` stores windows in a `ConcurrentHashMap`. Two consequences: the limit is
per instance, so N replicas mean N times the attempts, and it resets on restart. Keying purely on
email also means anyone who knows an address can deliberately lock that user out.

**Fixed.** The counter moved to Redis, and login now carries two budgets rather than one:

- **Per account**, tight (10 failures / 15 min). Somebody retrying their own password a dozen times
  has stopped remembering and started guessing.
- **Per source address**, much looser (50 / 15 min). A shared office IP or a NAT gateway
  legitimately produces many failures from many different people, so setting this near the
  per-account number would lock out a whole building.

Either budget alone is wrong in a different direction. Counting only by account is what let anyone
who knew an address lock that person out. Counting only by source lets an attacker spread guesses
across many accounts from one host and never trip anything.

**A success clears only the account's budget, never the source's.** Otherwise an attacker holding
one valid account of their own would reset the source counter at will and guess against everybody
else for free.

Verified live: 10 wrong passwords then `429`, the correct password also refused while the budget is
spent, and — the point of the fix — a *different* account logging in successfully from the same
source at the same moment.

**Known weakness.** The source comes from `X-Forwarded-For`, which is forgeable when nothing strips
it at the edge, so an attacker can rotate the value for a fresh source bucket each time. That is
why this is defence in depth on top of the per-account budget rather than a replacement for it, and
why making that header trustworthy belongs to the ingress.

---

## 10. JWT issuer and audience are not validated — Low

`JwtAuthenticationFilter` calls `.verifyWith(key)` and nothing else. Signature and expiry are
checked (jjwt does expiry automatically, and `verifyWith(SecretKey)` will not accept `alg: none` or
an asymmetric algorithm, so there is no algorithm-confusion hole here). But the `iss` claim is
written and never read.

**What it lets someone do.** Nothing today, because the key is unique to this platform. It matters
the moment that key is shared with any other system — then a token minted for a different audience
is accepted here.

**Fix.** `.requireIssuer("openpay")`. One line, now applied.

---

## 11. CORS ships a development origin as its default — Low

`allowed-origins` defaults to `http://localhost:5173` in both gateway and auth-service. A deployment
that forgets to set `OPENPAY_DASHBOARD_ORIGINS` silently trusts a developer's laptop origin rather
than failing. Low impact — an attacker cannot easily control that origin — but the fail-open default
is inconsistent with how the admin token and the JWT secret behave.

**Fixed.** Defaults to empty in both services, so a deployment that forgets to set
`OPENPAY_DASHBOARD_ORIGINS` answers no cross-origin request at all rather than silently trusting a
developer's laptop. `scripts/run-local.ps1` names the origin explicitly for local work, and the
compose file *requires* it rather than defaulting.

---

## 12. No TLS anywhere — Info, accepted

All service-to-service traffic is plain HTTP, including API keys and session tokens in headers.
Correct for a local compose stack; in a real deployment this belongs to the service mesh or ingress
layer rather than to application code.

## 13. Outdated dependencies carrying known CVEs — Critical, fixed (1 accepted)

`docker scout cves` against the built images found **30 known vulnerabilities across 13 packages
(5 critical, 25 high)**, all coming from what Spring Boot 3.5.4's dependency-managed versions
pulled in:

| Package | Fixed in | Worst finding |
| --- | --- | --- |
| `tomcat-embed-core` 10.1.43 | 10.1.55 | 4× **CRITICAL** (CVSS up to 9.8) — improper authentication, improper input validation, improper authorization |
| `jackson-core` 2.19.2 | 2.21.4 | HIGH (CVSS 8.7) — unbounded resource allocation |
| `kafka-clients` 3.9.1 | 3.9.2 | HIGH (CVSS 8.7) — race condition |
| `spring-kafka` 3.3.8 | 3.3.16 | HIGH (CVSS 8.1) — deserialization of untrusted data |
| `spring-boot` 3.5.4 | 3.5.14 | HIGH (CVSS 7.0) — insecure temporary file |
| `spring-core` 6.2.9 | 6.2.11 | HIGH (CVSS 7.5) — improper authorization |
| `spring-expression` 6.2.9 | 6.2.19 | HIGH (CVSS 7.5) — inefficient algorithmic complexity |
| `spring-data-commons` 3.5.2 | 3.5.12 | HIGH (CVSS 7.5) — uncontrolled resource consumption |

**Fix:** bumped `spring.boot.version` from `3.5.4` to `3.5.16` — the newest patch release in the
same minor line. Read `spring-boot-dependencies-3.5.16.pom` directly to confirm before assuming:
its own BOM already manages `tomcat.version=10.1.55`, `jackson-bom.version=2.21.4`, and
`kafka.version=3.9.2`, so the one-line version bump resolves all eight rows above without a single
per-artifact override, and without the migration risk a 4.x major bump would carry. Full reactor
rebuild and test suite (17 modules, unit + integration) passed unchanged afterward.

**One finding accepted rather than fixed:** `golang.org/x/net@0.40.0`, CVE-2026-39821, CRITICAL,
fixed upstream at 0.55.0. This is not an application dependency — it is baked into the official
`eclipse-temurin:21-jre` base image's own build tooling (confirmed via
`docker scout cves eclipse-temurin:21-jre --platform linux/amd64`, and Adoptium's own provenance
attestation). `docker scout` reports the currently pulled tag as already up to date, meaning
Adoptium has not yet republished a fixed layer. Checked the Alpine-based Temurin variant as an
alternative: it removes this finding but introduces two HIGH, **currently unpatched** CVEs of its
own (`sqlite` via the Alpine build toolchain), so switching base images trades one unfixed issue
for two. Staying on `eclipse-temurin:21-jre` and tracking Adoptium's release feed is the better
choice until a patched tag appears. Not exploitable over the network as deployed here — it is a
build-tooling artifact embedded in the image, not a library loaded by the running JVM at runtime.

---

## What was checked and found sound

Worth recording, so a future reader knows these were examined rather than missed:

- **Credentials at rest.** API keys are SHA-256 with the plaintext returned exactly once; passwords
  are BCrypt. SHA-256 is correct for API keys — they are high-entropy random values, so the slow
  hashing that passwords need buys nothing.
- **Constant-time comparison** in `AdminTokenFilter`, `SignatureVerifier`, and API key validation.
- **Account enumeration.** Login runs BCrypt against a dummy hash when no user exists, so a missing
  account and a wrong password take the same time and return the same message. Key validation
  likewise does not reveal whether a prefix was real.
- **Tenant isolation on every merchant-facing read.** Queries filter by the merchant from the
  validated credential, and return 404 rather than 403 so existence is not disclosed.
- **Merchant identity is never client-supplied.** A spoofed `X-Merchant-Id` is ignored; the
  acceptance suite asserts it.
- **Inbound callbacks** verify HMAC over the raw body before parsing, fail closed for unknown
  providers, and dedupe on the provider's own event id.
- **The ledger is append-only by database trigger**, not by application convention.
- **Money handling.** Integer minor units throughout, and Jackson is configured to reject a
  fractional amount rather than truncate it.
- **The payment state machine** has no merchant-facing transition endpoint. Only an event can move a
  payment.
- **Instrument tokens are never stored** — only a network and last four, or a masked VPA.

---

## Surfaces added since

Each of these was a new way to reach something, and each was placed on a tier deliberately rather
than on whichever one was nearest.

### The fraud gate — internal tier

`POST /internal/fraud/checks` is service-to-service and never merchant-facing. A merchant who could
call it could binary-search the thresholds, which is most of the work of evading them.

The request carries an instrument **type** only — `CARD`, `UPI` — and never a token, PAN fragment,
or VPA. Screening does not need them, and a risk service accumulating a second copy of payment
instruments is a breach waiting for a reason.

### Risk rules — admin tier, not ops

Everything else an operator does sits on the ops token. Rule editing does not, because someone who
can edit a rule can lower a threshold, let one specific payment through, and raise it again —
leaving nothing in the review queue and no trace that anything was let through. That is closer in
authority to issuing a credential than to reading a report.

### The routing table — admin tier, on its own prefix

Same reasoning, plus one thing specific to it: a routing rule contains a **base URL**. Someone who
can write one can point every payment on the platform at a host of their choosing. That is the
strongest capability added in this release, and it is behind the strongest token.

It sits on `/internal/routing-rules` rather than under `/internal/router`, which the service token
already guards. A path covered by both filters would require both credentials — a confusing way to
express "operators only", and the kind of confusion that gets resolved by widening something.

### Dead-letter replay — ops tier, with an allowlist

Replaying puts a message back into the payment flow, which mints no credential and so sits with the
ledger and settlement runs.

The `topic` parameter is checked against a **per-service allowlist**, and that check is the control
that matters: replay publishes to a topic derived from the request, so accepting an arbitrary one
would turn this endpoint into a way to inject any event into the platform using nothing but the ops
token. An acceptance check asserts a topic the service does not consume is refused.

Peek does not commit, and auto-commit is explicitly disabled on the replay consumer — a peek that
consumed what it displayed would be a way to destroy evidence by looking at it.

### The audit log — ops tier, read-only

Deliberately *not* the admin tier, even though its entries are mostly about admin actions:
investigating an incident should not require holding the credential that could have caused one.

There is no write endpoint. Entries are written by the code doing the thing being recorded, so
nobody holding a token can manufacture history. Nothing recorded is usable as a credential — key
issuance stores the prefix, rotation stores that it happened and never the secret.

### Two accepted weaknesses, stated plainly

**Screening fails open.** When fraud-service is unreachable, payments are accepted unscreened. This
is a deliberate availability trade — see
[ADR-0003](adrs/0003-fraud-gate-fails-open.md) — and the mitigation is that it is *visible*: the
payment is recorded `UNSCREENED` rather than `ALLOWED`, and it is a tagged series on the dashboard.
The residual risk is that nothing pages on it, so an unnoticed fraud-service outage is an unnoticed
window of unscreened traffic.

**Audit failures are swallowed.** A failed insert is logged at ERROR and the action proceeds, so
someone who can break writes to `audit_logs` can act unlogged. That requires database access, at
which point the audit log was never the control holding them back — and the alternative turns an
audit-table outage into nobody being able to sign in.

### What did not change

The tier model itself. Adding four surfaces was the first real test of whether the split in finding
6 was the right one, and each new endpoint had an obvious home under it. Two of them landed on the
admin tier despite being operator work, which is the model doing its job rather than a sign it is
wrong.
