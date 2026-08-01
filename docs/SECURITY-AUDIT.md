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
| 5 | No replay window on inbound acquirer callbacks | **Medium** | Open |
| 6 | One admin token opens everything | **Medium** | Open |
| 7 | No rate limiting on merchant-facing writes | **Medium** | Open |
| 8 | notification-service holds the platform admin token | **Medium** | Partly addressed |
| 9 | Login limiter is in-memory and keyed only on email | **Low** | Open |
| 10 | JWT issuer and audience are not validated | **Low** | Fixed |
| 11 | CORS ships a development origin as its default | **Low** | Open |
| 12 | No TLS anywhere | **Info** | Accepted |

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

**Fix.** Validate the URL when it is set: `https` (with `http` allowed only for loopback in dev),
no credentials in the URL, and refuse loopback, link-local, and private address ranges.

---

## 5. No replay window on inbound acquirer callbacks — Medium

**Where.** `WebhookController` takes `X-Provider-Signature` and a body. There is no timestamp header
and no freshness check. The only replay defence is the unique constraint on
`(provider, provider_event_id)`.

**What it lets someone do.** Anyone who captures one signed callback holds a message that stays
valid forever. Today the dedup table blocks the replay — but that table has no retention policy, so
the protection depends on rows never being deleted. Prune it for size, as you eventually will, and
every pruned event becomes replayable. A replayed capture callback moves a payment to `CAPTURED`.

**Fix.** Sign `timestamp.body` rather than `body`, require the header, and reject anything outside a
five-minute tolerance. That makes freshness a property of the signature instead of a property of
the database.

---

## 6. One admin token opens everything — Medium

`OPENPAY_ADMIN_TOKEN` is a single shared secret that authorises merchant onboarding, API key
issuance, dashboard user creation, the general ledger, settlements and payouts, webhook delivery
history, and merchant signing secrets. Five services compare against the same string. There is no
separation of duties and no way to grant read-only operator access, and rotating it means rotating
it everywhere at once.

**Fix.** Distinct credentials per capability, or a real operator identity with scoped permissions.

---

## 7. No rate limiting on merchant-facing writes — Medium

`ValidationAttemptLimiter` throttles failed key validations and failed logins. Nothing throttles
*successful* authenticated traffic. A merchant with a valid key can create payments as fast as it can
open connections, and each one fans out to Kafka, the router, an acquirer, the ledger, settlement,
and an outbound webhook. One misbehaving integration is a platform-wide incident.

**Fix.** A per-merchant token bucket at the gateway, backed by Redis (already in the compose file
and currently unused) so the limit holds across replicas.

---

## 8. notification-service holds the platform admin token — Medium

It needs merchant signing secrets, and the only way to read them is
`GET /api/v1/merchants/{id}/webhook-config`, which is admin-gated. So the service that makes
outbound connections to arbitrary third-party URLs — the one most exposed to the outside world — is
also the one holding the credential that opens the ledger and issues API keys.

**Fix.** A narrow internal endpoint that returns only a signing secret, authenticated by a
service-specific credential rather than the platform admin token.

**Partly addressed.** The credential this needs now exists — `X-Internal-Token`, already used
between payment-service and the router — so the remaining work is to move
`/api/v1/merchants/{id}/webhook-config` onto it. notification-service still holds the admin token
until that lands.

---

## 9. Login limiter is in-memory and keyed only on email — Low

`ValidationAttemptLimiter` stores windows in a `ConcurrentHashMap`. Two consequences: the limit is
per instance, so N replicas mean N times the attempts, and it resets on restart. Keying purely on
email also means anyone who knows an address can deliberately lock that user out.

**Fix.** Move the counter to Redis, and rate-limit by source as well as by account.

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

**Fix.** Default to empty, as the other secrets do.

---

## 12. No TLS anywhere — Info, accepted

All service-to-service traffic is plain HTTP, including API keys and session tokens in headers.
Correct for a local compose stack; in a real deployment this belongs to the service mesh or ingress
layer rather than to application code.

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
