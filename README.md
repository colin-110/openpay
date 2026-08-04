# OpenPay

A payment gateway, built the way a real one has to work: eleven services, one Kafka event
backbone, a double-entry ledger a database trigger enforces, and money handled as integer minor
units end to end. Every payment goes through idempotency, risk screening, provider failover, and a
transactional outbox — not because a demo needs it, but because a system that moves money doesn't
get to skip any of them.

## The problem this solves

Taking a payment is easy. Taking a payment *correctly* is not, and almost none of the difficulty
is in the happy path. The hard parts are the five ways it goes wrong:

| The failure | What it costs | What this platform does about it |
| --- | --- | --- |
| A customer taps "Pay" twice, or the network retries a request that already succeeded | The card is charged twice, and someone has to find and refund it | [Idempotency](#idempotency) keyed on the request *and* its body, enforced by a unique index — a retry returns the original payment, a different body under a reused key is refused |
| The acquiring bank goes down mid-flight | Payments are refused while the merchant is open for business | [Provider failover](#routing-rules) with a per-acquirer circuit breaker. Measured: **100% of payments still accepted** with an acquirer pulled out of rotation mid-run |
| The service crashes between saving a payment and announcing it | The payment exists but nothing downstream ever hears about it — no capture, no settlement, no ledger entry | A [transactional outbox](#event-delivery): the row and the event commit in one transaction, and a relay publishes afterwards. A crash replays; it does not lose |
| The books stop balancing | Nobody can say which payment is wrong, and every reconciliation after it is suspect | A [double-entry ledger](#the-ledger) with an append-only journal enforced by a database trigger, not by application code that a future bug can bypass |
| An attacker replays a captured "payment succeeded" callback from the bank | Goods ship for a payment that never settled | [Signature verification](#merchant-webhooks) over `timestamp.body`, so a captured callback expires instead of staying valid forever, plus deduplication on the provider's own event id |

Every one of those is a claim that can be checked rather than believed, and
[Measured performance](#measured-performance) is where the checking is written down — including
the two bugs that checking found.

This is a portfolio project, not a production system, and it says so throughout — every
architectural trade-off is written down with the alternative it gave up and why, every known
limitation is stated rather than hidden (see [Status](#status) and
[Limitations](#limitations-and-what-id-do-next)), and every claim in this README has been exercised
against the running system, not just written down: the numbers in
[Measured performance](#measured-performance) are from real k6 runs on a named machine, the
failure-mode table was verified by pausing real containers, and the Kubernetes deployment was
walked through an actual login and an actual payment on a real cluster, not validated as YAML.

**Start here:**

- [Getting Started](#getting-started) — one command, the whole platform
- [Measured performance](#measured-performance) — throughput, p50/p95/p99, and where it breaks
- [Architecture](docs/ARCHITECTURE.md) — what every component is, and what breaks when one dies
- [Status](#status) — what's built, and what's deliberately not
- [Limitations](#limitations-and-what-id-do-next) — what's missing, and what I'd fix first

<details>
<summary><strong>Table of contents</strong></summary>

- [The problem this solves](#the-problem-this-solves)
- [Services](#services)
- [Credentials](#credentials)
- [Getting Started](#getting-started)
- [Measured performance](#measured-performance)
- [API Walkthrough](#api-walkthrough)
- [Merchant Dashboard](#merchant-dashboard)
- [Sessions and refresh tokens](#sessions-and-refresh-tokens)
- [Email notifications](#email-notifications)
- [Payment Methods](#payment-methods)
- [Acquirer Attempts](#acquirer-attempts)
- [Endpoints](#endpoints)
- [Idempotency](#idempotency)
- [How a Payment Actually Flows](#how-a-payment-actually-flows)
- [Payment State Machine](#payment-state-machine)
- [The Ledger](#the-ledger)
- [Refunds](#refunds)
- [Observability](#observability)
- [The Audit Trail](#the-audit-trail)
- [Routing Rules](#routing-rules)
- [Risk Screening](#risk-screening)
- [Settlement](#settlement)
- [Merchant Webhooks](#merchant-webhooks)
- [Event Delivery](#event-delivery)
- [Testing](#testing)
- [Repository Layout](#repository-layout)
- [Status](#status)
- [Limitations and what I'd do next](#limitations-and-what-id-do-next)
- [Documentation](#documentation)

</details>

## Services

| Service | Port | Owns | Responsibility |
| --- | --- | --- | --- |
| `gateway-service` | 8080 | — | Front door. Authenticates merchant API keys and routes to the service that owns each path. |
| `auth-service` | 8081 | `openpay_auth` | Issues and validates API keys. Stores only key hashes. |
| `merchant-service` | 8082 | `openpay_merchant` | Merchant onboarding and lookup. |
| `payment-service` | 8083 | `openpay_payment` | Payment creation, idempotency, the state machine, and the transactional outbox. |
| `webhook-service` | 8084 | `openpay_webhook` | Trust boundary for inbound provider callbacks: signature verification and deduplication. |
| `provider-router-service` | 8085 | `openpay_router` | Chooses an acquirer, fails over, and trips a circuit breaker on a bad one. |
| `ledger-service` | 8086 | `openpay_ledger` | Double-entry journal. Append-only, enforced by the database. |
| `settlement-service` | 8087 | `openpay_settlement` | Accrues payables on capture and batches them into merchant payouts. |
| `notification-service` | 8088 | `openpay_notification` | Delivers signed webhooks to merchants, with retries and a delivery log. |
| `fraud-service` | 8089 | `openpay_fraud` | Screens payments against rules held in a table, and owns the review queue. |
| `vault-service` | 8091 | — | Turns a card into a single-use token. The only service that sees a card number, and the only one with no database at all. |
| `mock-bank-service` | 9001 / 9002 | — | Simulated acquirers. One codebase, run twice as `mock-bank-a` and `mock-bank-b`. |
| `demo-storefront` | 8090 | — | A shop that does not exist, taking payments that really happen. Holds its API key server-side, exactly as a merchant integration must. |

Shared code lives in `libs/`: `common-observability` (correlation IDs), `common-security`
(API key and admin token authentication, applied per path by configuration), `common-kafka`
(topic names, the event envelope, and the JSON event contracts every service agrees on), and
`common-outbox` (the transactional outbox, extracted once a second service needed to publish
events atomically with its own writes), and `common-audit` (the audit trail, on the same
shared-code-per-service-table arrangement as the outbox).

## Credentials

Four kinds of caller, five kinds of credential:

- **Merchant API key** (`X-Api-Key`) — for payment traffic. Issued by auth-service, presented by
  merchants. Merchant identity is derived from the validated key and never read from a
  client-supplied header. This one is a secret and must stay on a server.
- **Publishable key** (`X-Api-Key`, prefix `opk_pub_`) — the only credential here meant to be
  *read by strangers*. It goes in a checkout page, where anyone can lift it out of the developer
  tools, and the security model is not "nobody will look": it carries scope `tokens:create`, which
  may exchange a card for a single-use token and is refused by every read and write path on the
  platform. A different prefix on purpose, so that a key in a log or a screenshot answers "how bad
  is this?" without a database lookup.
- **Dashboard session** (`Authorization: Bearer`) — for people. A 15-minute HS256 JWT issued by
  `POST /api/v1/auth/login`, accepted on exactly the same paths as an API key. Downstream code
  never learns which of the two was used: a payment read is scoped to a merchant either way.
  Login also returns a **refresh token** — a separate, longer-lived, revocable credential the
  dashboard uses to renew the session silently in the background. See
  [Sessions and refresh tokens](#sessions-and-refresh-tokens).
- **Admin token** (`X-Admin-Token`) — for actions that create a business identity or a credential:
  onboarding merchants, issuing API keys, creating dashboard users, rotating a webhook secret.
- **Ops token** (`X-Ops-Token`) — for operator reporting and administration that mints nothing: the
  general ledger, closing a settlement window, cross-merchant delivery history. Separate from the
  admin token so the credential a reporting dashboard carries cannot also onboard a merchant.
- **Service token** (`X-Internal-Token`) — for service-to-service calls. Separate again: a service
  that reads one thing from a peer should not have to hold the credential that opens everything
  else.

A credential also carries an **authority** — a key's scope (`payments:read` / `payments:write`) or
a session's role (`MERCHANT_ADMIN` / `MERCHANT_VIEWER`) — and it is enforced. Read-only credentials
can see payments but cannot take one or refund one.

None of the admin token, the ops token, the service token, or the JWT secret has a default value. A
shipped default would be a publicly known secret, so each tier fails closed until its variable is
set, and auth-service refuses to start unless `OPENPAY_JWT_SECRET` is at least 32 bytes. The
browser origin (`OPENPAY_DASHBOARD_ORIGINS`) is empty by default for the same reason — an unset
origin answers no cross-origin request rather than trusting a developer's laptop.

## Getting Started

### 1. Start infrastructure

```bash
docker compose -f platform/docker/docker-compose.yml up -d
```

### 2. Set the credentials

PowerShell:

```bash
$env:OPENPAY_ADMIN_TOKEN = "dev-admin-token"; $env:OPENPAY_OPS_TOKEN = "dev-ops-token"; $env:OPENPAY_INTERNAL_TOKEN = "dev-internal-token"; $env:OPENPAY_JWT_SECRET = "dev-jwt-secret-not-for-production-use"; $env:OPENPAY_DASHBOARD_ORIGINS = "http://localhost:5173,https://localhost:5443"
```

bash:

```bash
export OPENPAY_ADMIN_TOKEN=dev-admin-token OPENPAY_OPS_TOKEN=dev-ops-token OPENPAY_INTERNAL_TOKEN=dev-internal-token OPENPAY_JWT_SECRET=dev-jwt-secret-not-for-production-use OPENPAY_DASHBOARD_ORIGINS=http://localhost:5173,https://localhost:5443
```

Every service gets the same signing key: auth-service issues sessions and the services behind the
gateway verify them. `scripts/run-local.ps1` sets all of these for you.

**These variables only exist in the terminal session you set them in.** If you close that window,
open a new tab, or run a later command through a different shell, they are gone and step 4 fails
with `required variable ... is missing a value`. Set them again in whichever terminal actually runs
the `docker compose` command — every time you use a new one.

### 3. Build

```bash
./mvnw clean verify
```

`test` runs the unit tests. `verify` additionally runs the `*IT` integration tests, which start a
real PostgreSQL via Testcontainers and require Docker to be running.

### 4. Run everything in Docker

The whole platform — infrastructure, all eleven backend services, and the merchant dashboard — from
one command:

```bash
docker compose -f platform/docker/docker-compose.yml -f platform/docker/docker-compose.apps.yml up -d --build
```

Services start in dependency order and wait on each other's health checks, so the first run takes a
few minutes (building thirteen images) and then everything is up:

| | |
| --- | --- |
| API (via the gateway) | `http://localhost:8080` |
| Dashboard | `http://localhost:5173` |
| Grafana | `http://localhost:3000` |
| Prometheus | `http://localhost:9090` |

The demo storefront is deliberately **not** in that list. It is a merchant integration rather than
part of the platform — on a real deployment it belongs on its own host entirely
([Deploying](docs/DEPLOY.md#6-the-shop-on-a-second-host)) — so it sits behind a compose profile and
starts in its own step:

```bash
docker compose -f platform/docker/docker-compose.yml -f platform/docker/docker-compose.apps.yml --profile shop up -d
```

That is the whole setup. A one-shot `demo-provisioner` container onboards a demo merchant, mints
the shop's two API keys and a dashboard login, writes them to a volume the shop reads, and exits.
Nothing is pasted anywhere by hand.

The provisioner holds the admin token so that the shop does not have to, which is the point rather
than an implementation detail: a merchant integration that could onboard merchants and mint
credentials would not be demonstrating anything about how merchants actually work.

The shop is then at `http://localhost:8090`. Put something in the basket, pay with one of the test
cards it offers, and watch the payment reach captured on its own — then follow the link to the
dashboard, whose credentials the shop prints on its own page, and find the same payment from the
merchant's side.

Tear it down with:

```bash
docker compose -f platform/docker/docker-compose.yml -f platform/docker/docker-compose.apps.yml down
```

Add `-v` to also drop the database volumes, for a genuinely clean slate.

One Dockerfile builds every backend service. The build stage is identical for all of them, so
Docker builds the reactor once and each image reuses that layer; only the final `COPY` differs.
Images run as a non-root user and size their heap from the container's memory limit rather than a
hard-coded `-Xmx`. The dashboard is a static React build, so it has its own two-line Dockerfile in
`web/dashboard/` — a Node build stage, then plain nginx serving the output.

### TLS

The stack above is plain HTTP. A `caddy` container also comes up alongside it, terminating TLS at
the edge exactly the way `platform/k8s/40-ingress.yaml` does in a real cluster — the services
themselves never see HTTPS, only the boundary a browser or an external party actually touches does:

| | |
| --- | --- |
| API (via the gateway) | `https://localhost:8443` |
| Login only | `https://localhost:8444/api/v1/auth/login` |
| Acquirer webhooks | `https://localhost:8445` |
| Dashboard | `https://localhost:5443` |

The plain-HTTP ports above still work unchanged — nothing is removed, TLS is only added in front.

The certificate is self-signed by Caddy's own locally-generated CA (there is no public DNS name to
prove ownership of on a laptop), so the browser shows a one-time trust warning the first time it
sees it — normal for any local HTTPS setup. To make that warning go away, trust the CA Caddy
generated:

```bash
docker exec openpay-caddy cat /data/caddy/pki/authorities/local/root.crt > openpay-local-ca.crt
```

Then import `openpay-local-ca.crt` into your OS or browser's trusted root store. Once trusted, every
`https://localhost:...` URL above is a green padlock with no warning.

`OPENPAY_DASHBOARD_ORIGINS` needs both origins if you want the dashboard to work whichever port you
load it from: `http://localhost:5173,https://localhost:5443`. The example in step 2 above already
includes both.

Kafka advertises two addresses, because the right one depends on who is asking: containers resolve
`kafka:29092`, while a process on the host cannot and uses `localhost:9092`. That is what lets the
containerised stack and the Maven workflow below coexist without reconfiguration.

### 5. Or run services from Maven against Dockerised infrastructure (Windows)

Twelve processes in twelve terminals is enough friction to stop anyone actually running this, so
there is a helper. It checks that infrastructure is up, launches each service in its own window,
and waits until all of them report healthy:

```bash
.\scripts\run-local.ps1
```

Stop them all with:

```bash
.\scripts\run-local.ps1 -Stop
```

Then run the acceptance suite against the live stack:

```bash
bash scripts/e2e.sh
```

### 6. Or run the services individually

Each service needs its own terminal. `-am` builds the shared libraries it depends on.

For the asynchronous flow you also need matching signing secrets, so webhook-service can verify
what the banks send:

```bash
export MOCK_BANK_A_SECRET=bank-a-secret
export MOCK_BANK_B_SECRET=bank-b-secret
```

```bash
./mvnw -pl services/merchant-service -am spring-boot:run
```

```bash
./mvnw -pl services/auth-service -am spring-boot:run
```

```bash
./mvnw -pl services/payment-service -am spring-boot:run
```

```bash
./mvnw -pl services/gateway-service -am spring-boot:run
```

```bash
./mvnw -pl services/webhook-service -am spring-boot:run
```

```bash
./mvnw -pl services/provider-router-service -am spring-boot:run
```

```bash
./mvnw -pl services/ledger-service -am spring-boot:run
```

```bash
./mvnw -pl services/settlement-service -am spring-boot:run
```

```bash
./mvnw -pl services/fraud-service -am spring-boot:run
```

The two acquirers are the same module run twice with different configuration:

```bash
BANK_NAME=mock-bank-a BANK_PORT=9001 BANK_SIGNING_SECRET=bank-a-secret ./mvnw -pl services/mock-bank-service -am spring-boot:run
```

```bash
BANK_NAME=mock-bank-b BANK_PORT=9002 BANK_SIGNING_SECRET=bank-b-secret ./mvnw -pl services/mock-bank-service -am spring-boot:run
```

Start order matters for a full flow: auth-service verifies merchants against merchant-service, and
payment-service verifies API keys against auth-service.

### 7. Watch failover happen

Kill `mock-bank-a` and create a payment. The router records a failed attempt against A, succeeds on
B, and after three consecutive failures stops calling A at all:

```bash
curl http://localhost:8085/internal/router/providers -H "X-Internal-Token: dev-internal-token"
```

```bash
curl "http://localhost:8080/api/v1/payments/<PAYMENT_ID>/attempts" -H "X-Api-Key: <API_KEY>"
```

Each acquirer can also be made to misbehave on purpose with `BANK_DECLINE_RATE`,
`BANK_TIMEOUT_RATE`, and `BANK_UNAVAILABLE`.

## Measured performance

Numbers, not adjectives. Every figure below was produced by a k6 run against the full stack on a
named machine, and the raw output plus the caveats are in
[tests/performance/baseline.md](tests/performance/baseline.md).

**Where it was measured:** one Windows 10 laptop, Docker Desktop with 12 vCPUs / 7.4 GiB, running
*all 22 containers at once* — eleven Spring Boot services, Postgres, Kafka, Redis, Prometheus,
Grafana, Loki, and the rest. This is a deliberately hostile setup for a throughput number, and it
is stated plainly because a benchmark without its hardware is a number that means nothing.

### Payment creation — sustained write load

The full write path: authenticate at the gateway, screen for risk, persist the payment and its
outbox row in one transaction. Four rate tiers, 45 seconds each, 14,401 payments total.

| Target rate | Achieved | Payments | p50 | p95 | p99 | Errors | Dropped |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 20/s | 20.0/s | 900 | 80ms | 659ms | 947ms | **0.00%** | 0 |
| 50/s | 50.0/s | 2,251 | 48ms | 90ms | 160ms | **0.00%** | 0 |
| 100/s | 100.0/s | 4,500 | 46ms | 98ms | 250ms | **0.00%** | 0 |
| 150/s | 150.0/s | 6,750 | 49ms | **220ms** | **341ms** | **0.00%** | 0 |

**150 payments/second at p95 220ms, zero failures, zero dropped requests** — and 150/s was the
highest tier run, not a ceiling that was found. The 20/s row is the worst on the table purely
because it ran first on cold JVMs and paid for the JIT compilation every later tier inherited.

An earlier set of runs on the same host reported p95 = 1.89s at 100/s — twenty times worse. Both
happened; the difference was that those ran back-to-back with no gap, so each started while the
platform was still draining the previous one. Both sets of numbers are kept in
[baseline.md](tests/performance/baseline.md) rather than the inconvenient one deleted, because
"the load generator's schedule changed the answer by 20×" is a more useful thing to know than a
single tidy figure.

> [!IMPORTANT]
> **This table has been superseded, and the honest number is lower.** Every run above drove a
> single merchant, so the seeded `merchant-velocity-burst` rule (100 payments per merchant per
> minute) held the great majority of them for review. A held payment is deliberately *not*
> announced for routing — no acquirer, no ledger, no settlement — and it still returns 201, so k6
> counted it as a success. These figures measured roughly a tenth of the work a payment does.
>
> Re-measured across a pool of merchants, with every payment doing the whole job — three runs of
> the identical script, failure rate per tier:
>
> | Tier | Run A | Run B | Run C |
> | --- | --- | --- | --- |
> | 20/s | 0.0% | 0.0% | 0.0% |
> | 50/s | 0.0% | 0.0% | 0.0% |
> | 100/s | 10.0% | 0.0% | 9.7% |
> | 150/s | 41.4% | 4.6% | 0.0% |
>
> **At least 50 payments a second, sustained, with zero failures — and this host cannot honestly
> say more than that.** Above 50/s the tiers invert between runs (Run C passed 150/s cleanly while
> failing 100/s), which is the measurement being swamped by a host running twelve JVMs, three
> datastores and the load generator on twelve shared vCPUs. A ceiling needs hardware that is not
> also the system under test. Full account, including the two bugs the re-measurement found, in
> [baseline.md](tests/performance/baseline.md#stressjs--run-of-2026-08-03-after-the-merchant-pool-and-warm-up-fixes).

### Resilience — the claims, tested

These are the interesting numbers, because they are the ones a payment platform is actually judged
on. Each was produced by breaking something real and watching.

| What was done to it | Result |
| --- | --- |
| An acquirer pulled out of rotation mid-run (`provider-outage.js`) | **100.00% of payments still accepted** (2,401 / 2,401), p95 58ms — losing an acquirer cost nothing, not even latency |
| Callback traffic spiked 40× (10/s → 400/s, `webhook-spike.js`) | 35,515 callbacks, 0.00% errors, and **3,411 duplicate callbacks all correctly rejected** — no double-credit under load |
| Held at 25/s for 4 minutes (`soak.js`) | p95 90ms in the first half, **82ms in the second** — no drift, no leak, 0 failures |
| Redis paused (`fault-injection.sh`) | Rate limiting and login throttling fail open — **after a fix; this is where the Lettuce bug was found** |
| fraud-service paused | Screening fails open, payment recorded `UNSCREENED` rather than refused |
| auth-service paused | API-key payments refused (503); existing dashboard sessions keep working |
| Kafka paused, then restored | Payment creation still succeeds; the payment advances on its own when Kafka returns — **nothing lost** |

**13 of 13 fault-injection checks pass.** They are re-run by a script against real paused
containers, not asserted in prose: [`scripts/fault-injection.sh`](scripts/fault-injection.sh).
Each one names the line in [docs/ARCHITECTURE.md § 5](docs/ARCHITECTURE.md#5-failure-modes) it is
checking, so the failure-mode table cannot quietly drift away from what the system actually does.

### Four bugs the measurements found

The point of testing is finding things. Every one of these was invisible to a fully green test
suite, and three of them were bugs in the *tests* — which is its own lesson, because a test that
cannot fail is indistinguishable from a test that passes.

1. **Redis commands had no timeout.** The rate limiter and login throttle both caught the right
   exception and failed open correctly — but Lettuce's *default* command timeout is 60 seconds, so
   "fails open" actually meant "hangs for a minute, then fails open". Found by pausing the Redis
   container and watching. Fixing it turned a 22.3% failure rate at 50/s into 0.00%.
2. **A load-test threshold that could never fail.** The p95 latency threshold was written against
   `payment_create_duration{expected_response:true}`; that tag only exists on k6's built-in HTTP
   metrics, never on a custom trend, so it matched zero samples and passed every run ever recorded.
   Corrected, it immediately caught a 1.89s p95 that it had been reporting as green.
3. **A documented config override that did nothing.** The compose file never forwarded
   `RATE_LIMIT_PER_WINDOW` into the container, so the documented way to raise the rate limit for a
   load test silently changed nothing — meaning any run that trusted it measured the rate limiter
   instead of the write path.
4. **A fault-injection assertion that could never be observed.** The script checked that API-key
   payments are refused with 503 while auth-service is paused — but used an 8-second client
   timeout against a gateway whose own read timeout is 10 seconds. Since `docker pause` freezes a
   process without closing its sockets, the request hung the full 10s and curl gave up first,
   recording `000`. The check could never see the response it existed to assert.

A fifth finding was not a bug but a measurement error worth keeping: running the load scenarios
back-to-back with no drain gap made 100/s look 20× worse than it is
([the details](tests/performance/baseline.md)). The schedule of the test changed the answer more
than any code did.

## API Walkthrough

Onboard a merchant (admin):

```bash
curl -X POST http://localhost:8082/api/v1/merchants -H "X-Admin-Token: dev-admin-token" -H "Content-Type: application/json" -d '{"merchantCode":"shop-1","legalName":"Demo Shop","webhookUrl":null,"defaultCurrency":"INR"}'
```

Issue an API key for that merchant (admin). The plaintext key is returned exactly once:

```bash
curl -X POST http://localhost:8081/api/v1/api-keys -H "X-Admin-Token: dev-admin-token" -H "Content-Type: application/json" -d '{"merchantId":"<MERCHANT_ID>","name":"primary","scope":"payments:write","expiresAt":null}'
```

Create a payment through the gateway:

```bash
curl -X POST http://localhost:8080/api/v1/payments -H "X-Api-Key: <API_KEY>" -H "Idempotency-Key: order-1001" -H "Content-Type: application/json" -d '{"amount":250000,"currency":"INR"}'
```

That returns `201` with status `CREATED`. Nothing else is required: poll the payment and watch it
advance on its own as the router dispatches it and the acquirer calls back.

```bash
curl http://localhost:8080/api/v1/payments/<PAYMENT_ID> -H "X-Api-Key: <API_KEY>"
```

## Merchant Dashboard

A React SPA in `web/dashboard`. It is a client of the same public API a merchant would integrate
against — no private endpoints and no database of its own — so anything the dashboard can do, a
merchant can do over HTTP.

Five sections:

- **Overview** — captured volume, success rate, refunded value, and how many payments are still
  with an acquirer, over the most recent window of traffic. Figures state the window they cover
  rather than implying they cover everything, and are summed **per currency, then joined** rather
  than added together as raw integers — a merchant is not guaranteed to transact in only one.
- **Payments** — the full list, with the method each customer used, filtered by status on the server
  and paged on the server. Searching takes a whole payment ID, because an ID is a UUID and a
  substring search is not something the API can honestly answer.
- **Refunds** — every refund the merchant has issued, newest first, filtered by status.
- **Settlements** — payouts, with gross, fee and net, and the payments inside each one. Opening a
  row expands into its line items: a payout is only trustworthy if you can see what it is made of.
- **Developers** — the integration details, the events the platform sends, and the delivery log.
  "The webhook never arrived" is the most common integration complaint, and the answer is almost
  always the response code and error recorded here.

Selecting a payment opens a detail drawer: summary, the acquirer attempts behind it, an activity
timeline built from the timestamps the API actually returns, the refunds against it, and the refund
action itself. The open payment is part of the URL (`#/payments/<id>`), so a link to one payment is
a link somebody can send, and the drawer remounts on that ID rather than reusing state across two
different payments — the one thing in this dashboard that moves money does not get to run on a
stale fetch. Issuing a refund is itself two steps: type an amount, then confirm the exact figure
before it sends, the same pattern any real payments console gates a money-movement action behind.

The attempts section is fetched separately from the payment, so a router outage costs that panel
one section instead of the whole drawer — and it says the attempts could not be read rather than
showing an empty list.

The console polls every five seconds, because a payment reaches `CAPTURED` on its own a few seconds
after it is created. The **Live** toggle stops that, since a screen that reorders itself while you
are reading it is its own kind of wrong.

Seed a merchant, a dashboard user, and a spread of rupee payments and refunds:

```bash
OPENPAY_ADMIN_TOKEN=dev-admin-token ./scripts/seed-demo.sh
```

That prints the login it created.

**If the stack is running via `docker compose ... apps.yml`**, the dashboard is already up at
`http://localhost:5173` — it is one of the containers, built from `web/dashboard/Dockerfile` (a
static build served by nginx). No separate step.

**To run it outside Docker instead** — for `npm run dev`'s hot reload while editing it — start it
by hand:

```bash
cd web/dashboard && npm install && npm run dev
```

It talks to the gateway on 8080 and auth-service on 8081 either way, from the browser rather than
from a container, so the same defaults work in both cases. Point it elsewhere with `VITE_API_BASE`
and `VITE_AUTH_BASE`.

Amounts are integer minor units end to end — paise for INR, cents for USD — and only the display
layer knows about decimal places. Rupees are grouped the Indian way, so ₹12,50,000.00 rather than
₹1,250,000.00.

Because it runs on its own origin, both services it calls answer CORS preflights for
`OPENPAY_DASHBOARD_ORIGINS` (defaulting to the Vite dev server). No internal service does: a page
should not be able to reach past the gateway. The gateway also strips CORS headers coming back
from downstream, so exactly one component decides the answer.

The session lives in `sessionStorage` and is dropped on a `401`, so an expired token signs you out
rather than leaving an empty table. Amounts are integer minor units end to end; only the display
layer knows about decimal places.

## Sessions and refresh tokens

`POST /api/v1/auth/login` returns two credentials, not one:

- an **access token** — the HS256 JWT described above, good for 15 minutes, sent as
  `Authorization: Bearer` on every API call.
- a **refresh token** — a 32-byte random value good for 30 days, stored server-side the same way an
  API key is: SHA-256 hashed, never in the plain. It is not a bearer credential for API calls; it is
  only good for `POST /api/v1/auth/refresh`.

The dashboard schedules a silent refresh 60 seconds before the access token expires
(`web/dashboard/src/App.tsx`), so a session stays open across the 15-minute window without the user
ever seeing a login screen — closer to how a real bank dashboard behaves than a session that just
expires. Closing the tab still ends the session (`sessionStorage`, not `localStorage`), and
`POST /api/v1/auth/logout` revokes the refresh token so a stolen `sessionStorage` snapshot from a
closed tab cannot be replayed later.

Refresh tokens **rotate on every use**: refreshing returns a new access token *and* a new refresh
token, and the one presented is marked spent. Presenting an already-spent refresh token again is
treated as evidence the token was stolen — the server revokes every other active session for that
user, not just the one replayed. This is the same reasoning password managers and banking apps use
for "sign out everywhere": a stolen refresh token in transit is caught the first time anyone (the
legitimate client or the thief) tries to use the one that already got rotated away.

```bash
curl -X POST http://localhost:8081/api/v1/auth/refresh -H "Content-Type: application/json" -d '{"refreshToken":"<REFRESH_TOKEN>"}'
curl -X POST http://localhost:8081/api/v1/auth/logout -H "Content-Type: application/json" -d '{"refreshToken":"<REFRESH_TOKEN>"}'
```

Full reasoning and the theft-detection design is in
[SECURITY-AUDIT.md § Refresh tokens](docs/SECURITY-AUDIT.md#refresh-tokens--a-revocable-session-behind-a-stateless-access-token).

## Email notifications

Two events send an email; nothing else does. Both are cases where a webhook or a log line is not
enough, because the person who needs to know is not the one watching the platform:

- **Refresh-token reuse** (auth-service): the account holder gets a plain-language security alert
  when their sessions are revoked for suspected theft — see
  [Sessions and refresh tokens](#sessions-and-refresh-tokens) above. They are the only person who
  can say whether it was really them.
- **A webhook delivery is abandoned** (notification-service): an operator address
  (`OPENPAY_OPS_EMAIL`, unset — and so silent — by default) is told when a merchant's endpoint has
  failed enough times that delivery gives up. The row survives either way (`DeliveryStatus.ABANDONED`,
  queryable at `/internal/webhooks/deliveries`); what the email adds is someone actually finding out
  without going looking.

Neither call blocks the request it fires from — sending is `@Async` on its own small thread pool,
and a send that fails is logged and swallowed, the same "never break the thing it's reporting on"
rule `AuditRecorder` already follows for the audit trail. There is no real SMTP provider wired in:
locally and in Docker Compose, mail goes to [Mailpit](https://github.com/axllent/mailpit) —
`http://localhost:8025` shows every email actually sent, the same role mock-bank-a and mock-bank-b
play for acquirers. Point `SMTP_HOST`/`SMTP_PORT` at a real relay to send for real.

## Payment Methods

A payment can say how it is being paid:

```bash
curl -X POST http://localhost:8080/api/v1/payments -H "X-Api-Key: <API_KEY>" -H "Idempotency-Key: order-1002" -H "Content-Type: application/json" -d '{"amount":149900,"currency":"INR","paymentMethod":{"type":"upi","vpa":"colin.thomas@okhdfcbank","token":"tok_live_xyz"}}'
```

`type` is one of `card`, `upi`, `netbanking`, `wallet`. Everything else is optional, including the
whole object — an integration that sends only an amount still works, and a payment with no method
recorded reports that rather than guessing.

**What is kept, and what is not.** `token` is the instrument reference the acquirer needs. It is
accepted, used, and never written down: a card number, a CVV, or a reusable token has no business
in a payment row, and the only reason to keep one would be to do something this platform does not
do. What survives is the minimum needed to recognise a payment on a statement — a network and last
four digits, or a VPA with its local part masked, so `colin.thomas@okhdfcbank` is stored as
`co***@okhdfcbank`. The handle after the `@` names the bank, not the customer, so it is kept whole.

## Acquirer Attempts

A payment that succeeded on the second acquirer looks identical to one that succeeded on the first,
until you ask:

```bash
curl http://localhost:8080/api/v1/payments/<PAYMENT_ID>/attempts -H "X-Api-Key: <API_KEY>"
```

```json
[
  {"attemptNo": 1, "provider": "mock-bank-a", "status": "FAILED", "providerReference": null, "failureReason": "mock-bank-a call failed"},
  {"attemptNo": 2, "provider": "mock-bank-b", "status": "ACCEPTED", "providerReference": "mock-bank-b-cade6926", "failureReason": null}
]
```

provider-router-service owns this data, so payment-service reads it over HTTP rather than keeping a
second copy that could disagree with the first. It authorises before it asks: fetching the payment
throws for a payment belonging to someone else, so the router is only ever queried about payments
the caller can already see.

When the router is unreachable the endpoint returns `503 attempts_unavailable`. That matters —
"nothing was tried" and "could not ask" are different answers, and returning an empty list would
quietly state the wrong one.

## Endpoints

Merchant-facing, via the gateway on 8080, authenticated with either `X-Api-Key` or a dashboard
session in `Authorization: Bearer`:

- `POST /api/v1/payments` — create a payment. Requires `Idempotency-Key`. Optionally carries a
  `paymentMethod`.
- `GET /api/v1/payments/{paymentId}`
- `GET /api/v1/payments/{paymentId}/attempts` — which acquirers were tried, in order, and why each
  attempt ended. `503` when provider-router-service cannot be reached, never an empty list.
- `GET /api/v1/payments?page=0&size=20&status=CAPTURED` — `status` is optional.
- `POST /api/v1/refunds` — refund a captured payment. Requires `Idempotency-Key`. Omit `amount`
  to refund everything still refundable.
- `GET /api/v1/refunds/{refundId}`
- `GET /api/v1/refunds?paymentId={paymentId}` — every refund against one payment, oldest first.
- `GET /api/v1/refunds?page=0&size=20&status=SUCCEEDED` — every refund the merchant has made,
  newest first. Separate from the by-payment listing because the two answer different questions
  and page differently.
- `GET /api/v1/settlements?page=0&size=20` — the merchant's own payouts, newest window first.
- `GET /api/v1/settlements/{settlementId}` — a payout and the payments inside it.
- `GET /api/v1/webhooks/deliveries?page=0&size=20` — what the platform sent this merchant, what
  failed, and why. Scope comes from the credential and cannot be widened by a parameter.

Platform-operator, authenticated with `X-Admin-Token`:

- `POST /api/v1/merchants`
- `GET /api/v1/merchants/{merchantId}`
- `GET /api/v1/merchants?page=0&size=20`
- `POST /api/v1/api-keys`
- `POST /api/v1/users` — create a dashboard user for a merchant.
- `POST /api/v1/merchants/{merchantId}/webhook-secret` — rotate the signing secret, returned once.

Human-facing, on auth-service directly, unauthenticated by necessity:

- `POST /api/v1/auth/login` — returns an access token and a refresh token. An unknown email and a
  wrong password fail identically, so login cannot be used to discover who has an account.
- `POST /api/v1/auth/refresh` — trades an unexpired, unused refresh token for a new access token
  and a new refresh token. Reusing an already-rotated refresh token revokes every session the user
  has.
- `POST /api/v1/auth/logout` — revokes one refresh token. Idempotent: logging out a token that is
  already gone is success.

Internal, not exposed through the gateway:

- `POST /api/v1/auth/validate-key` — called by the gateway and payment-service.
- `POST /internal/provider/webhooks/{provider}` — acquirer callbacks. HMAC-signed over
  `timestamp.body` with `X-Provider-Signature` and `X-Provider-Timestamp`, refused outside a
  five-minute window, and deduplicated on the provider's own event id. The timestamp is inside the
  signature, so it cannot be rewritten to make a captured callback look fresh.
- `GET /internal/router/providers` — circuit breaker state per acquirer.
- `GET /internal/router/payments/{paymentId}/attempts` — what was tried, in order, and why each
  attempt ended.
- `GET /internal/merchants/{merchantId}/webhook-config` — the live signing secret, read by
  notification-service on each delivery.
- `POST /internal/fraud/checks` — the risk gate, called by payment-service inside payment creation.
  Never merchant-facing: a caller who could reach it could binary-search the thresholds.

Operator reporting and administration, authenticated with `X-Ops-Token`:

- `GET /internal/settlements` — every merchant's payouts.
- `POST /internal/settlements/run` — close a settlement window explicitly.
- `POST /internal/settlements/{settlementId}/complete` — mark a payout paid.
- `GET /internal/webhooks/deliveries?merchantId=` — delivery history across merchants.
- `GET /api/v1/ledger/entries?referenceId={paymentId}` — every transaction and both sides of each.
- `GET /api/v1/ledger/accounts/{accountCode}/balance` — derived from the journal.
- `GET /internal/dlq/topics`, `GET /internal/dlq?topic=`, and `POST /internal/dlq/replay|discard` —
  present on every consuming service, covering the topics it consumes.
- `GET /internal/fraud/reviews?merchantId=` — payments held by screening, oldest first.
- `POST /internal/fraud/reviews/{paymentId}/resolve` — release or refuse a held payment.
- `GET /internal/fraud/decisions/{paymentId}` — why one payment was judged the way it was.
- `GET /internal/audit?action=&merchantId=&page=&size=` — the audit trail. Present on auth-service
  and merchant-service, each covering its own actions. Read-only: there is no write endpoint, so
  nobody holding the token can manufacture history.

Risk rules, authenticated with `X-Admin-Token` rather than the ops token, because someone who can
edit a rule can lower a threshold, let one payment through, and raise it again without leaving
anything in the review queue:

- `GET /internal/fraud/rules` — every rule, disabled ones included, in evaluation order.
- `POST /internal/fraud/rules`
- `POST /internal/fraud/rules/{ruleId}/enable` and `/disable`

Routing rules, also `X-Admin-Token`, for the same kind of reason — editing one decides where every
payment on the platform goes, including to a base URL of the editor's choosing:

- `GET /internal/routing-rules` — the whole table, in evaluation order.
- `GET /internal/routing-rules/resolve?merchantId=&currency=&amount=` — what a hypothetical payment
  would be tried against. The cheapest way to check a change before making it.
- `POST /internal/routing-rules`
- `POST /internal/routing-rules/{ruleId}/enable`, `/disable`, and `/priority?priority=`

Every service exposes `/actuator/health`, `/actuator/info`, and `/actuator/prometheus`.

## Idempotency

`POST /api/v1/payments` requires an `Idempotency-Key`. The first request creates the payment and
returns `201`. Replaying the same key with the same body returns `200` and the original payment —
no second charge. Replaying the same key with a *different* body returns `409`, because that is a
client bug rather than a retry, and silently returning the original payment would hide it.

Concurrency is handled by a unique constraint on `(merchant_id, idempotency_key)`: if two requests
race, the loser catches the constraint violation and returns the winner's payment.

## How a Payment Actually Flows

Creating a payment returns immediately with `CREATED`. Everything after that happens on its own:

```text
POST /api/v1/payments
  └─> risk screening, synchronously, before anything is written
        ├─ BLOCK  ──> 422, nothing persisted
        ├─ REVIEW ──> payment stored as HELD, no event published, nothing routed
        └─ ALLOW  ──> continue
  └─> payment row + outbox row committed in ONE transaction
        └─> relay publishes payment.created.v1
              └─> provider-router picks an acquirer by priority, skipping any
                  whose circuit breaker is open
                    ├─ acquirer accepts ──> payment.provider-dispatched.v1
                    │                        └─> PENDING_PROVIDER
                    └─ acquirer refuses or hangs ──> next acquirer
                                                     all exhausted ──> FAILED
                          └─> acquirer POSTs a signed callback to webhook-service
                                └─> signature verified, duplicate rejected
                                      └─> provider.callback-received.v1
                                            └─> AUTHORIZED, then CAPTURED
```

A full run locally takes about three seconds from `201 Created` to `CAPTURED`.

## Payment State Machine

```text
CREATED ──> PENDING_PROVIDER ──> AUTHORIZED ──> CAPTURED
   │               │                  │
   └───────────────┴──────────────────┴──────> FAILED
   │                                  │
   └──────────────────────────────────┴──────> CANCELLED
```

`CAPTURED`, `FAILED`, and `CANCELLED` are terminal. The rule lives on the entity, so no caller can
bypass it, and concurrent updates are caught by an optimistic-locking `@Version` column.

**Merchants cannot move their own payments.** There is no status endpoint on the public API: only
a routing decision or a signature-verified provider callback advances a payment. Transitions
driven by events are deliberately tolerant — a redelivered callback asking for the state we are
already in is a no-op, because Kafka delivers at least once and acquirers re-send.

## The Ledger

Payments describe intent; the ledger records what the money actually did. When a payment is
captured, `ledger-service` posts one balanced transaction:

```text
payment.status-updated.v1 (CAPTURED, 25000 USD, merchant M)
  └─> transaction (reference: PAYMENT <id>)
        DEBIT   GATEWAY_CLEARING              25000 USD   asset,     platform
        CREDIT  MERCHANT_PAYABLE (merchant M) 25000 USD   liability, per-merchant
```

Funds arrived from the acquirer, so an asset rose; we now owe the merchant, so a liability rose.

Three properties are enforced rather than assumed:

- **Debits equal credits.** Checked before anything is written. An unbalanced journal cannot be
  repaired by a later correction — every report drawn from it is wrong from that moment on.
- **One event posts once.** A unique constraint on `ledger_transactions.event_id`, not a lookup,
  because a lookup loses to a concurrent redelivery. At-least-once delivery must not become
  at-least-once accounting.
- **The journal is append-only.** A database trigger rejects `UPDATE` and `DELETE` on entries and
  transactions, so the rule holds against any client, including a direct `psql` session.

Only `CAPTURED` posts. `AUTHORIZED` reserves funds without moving them and a failed payment moved
nothing, so posting either would inflate the books with money that does not exist.

Balances are derived by summing the journal, never stored in a column that could drift from it:

```bash
curl "http://localhost:8086/api/v1/ledger/accounts/MERCHANT_PAYABLE/balance?merchantId=<ID>&currency=USD" -H "X-Admin-Token: dev-admin-token"
```

## Refunds

A refund is its own resource, because a payment can be refunded in parts and "refunded" is a
running total against it rather than a point on its lifecycle.

```text
POST /api/v1/refunds            refund PENDING
  └─> refund.created.v1
        └─> router sends it back to the acquirer that took the payment
              └─> acquirer callback, signature verified
                    └─> refund SUCCEEDED
                          ├─> ledger reverses the capture
                          └─> settlement accrues a negative payable
```

The refundable balance is the payment amount minus everything already committed, and PENDING
refunds count towards that: left out, several concurrent requests could each pass their own check
and together refund more than the payment was worth. A FAILED refund releases its amount again.

A refund goes back through the acquirer that took the money, never a different one. There is no
failover here; sending it elsewhere would be asking a bank to return funds it never received. If
that acquirer is gone or refuses, the refund fails loudly rather than hanging.

The payment only becomes `REFUNDED` when every minor unit has come back; a partial refund leaves it
`CAPTURED`. The platform fee is not returned, which is how most gateways price a refund.

### Carry-forward

A refund is accrued as a negative payable, so it sits in the same pending pool as captures and nets
against them. Carry-forward falls out of that rather than needing its own mechanism.

Verified end to end on a 500.00 payment:

```text
capture 50000                       payable  50000
refund  20000                       payable  30000
settle                              payout gross 30000, fee 1000, net 29000; payable 0
refund  30000  (after settling)     payable -30000   <- merchant owes us
settle                              NO payout, deficit carried forward
capture 100000 arrives              
settle                              payout gross 70000  <- deficit absorbed
```

A negative payable is a receivable, not a bug: the merchant was paid for money they have since
given back. Paying out a negative amount is meaningless, and zeroing it would quietly write off
money owed, so the items stay pending and reduce the next payout instead.

## Observability

Three signals, and one place to look at them:

```bash
docker compose -f platform/docker/docker-compose.yml up -d
```

Grafana is on <http://localhost:3000>, anonymous viewer access enabled, with two dashboards
provisioned from `platform/observability/grafana/dashboards/`:

- **Service Health (RED)** — request rate, 5xx rate, and latency percentiles per service, plus heap
  and connection-pool usage. The first thing to open when something is wrong and you do not yet
  know what.
- **Payment Flow** — whether money is actually moving. Outbox backlog, review queue depth, open
  circuit breakers, capture rate, transitions, and acquirer outcomes.

The dashboards, datasources, and log shipping are all provisioned from files. Before that Grafana
and Loki were running but empty: every dashboard had to be rebuilt by hand on each fresh volume,
which meant nobody did.

### Why two dashboards and not one

Spring Boot already exports RED metrics per HTTP endpoint, and they answer "is the API healthy".
They cannot answer "are payments completing", because a payment that is accepted and then never
captured is two successful HTTP requests and one stuck customer. The business metrics count the
lifecycle instead of the requests.

The most important number on either dashboard is **outbox backlog**. Everything after payment
creation is event-driven, so a stalled relay fails nothing at all — payments are accepted,
committed, and then simply stop advancing. There is no error to alert on, and without this gauge
the first signal is a merchant asking why nothing has settled.

### Metric naming, the hard way

Two conventions, both found by scraping the real endpoint rather than by reading documentation, and
both now asserted by `MetricsExposureIT`:

- Meters are named with dots and **no** `_total` suffix. Micrometer's Prometheus registry appends
  it; writing the Prometheus name produces `..._total_total`, which matches nothing.
- A counter must not end in `created`. `_created` is a reserved OpenMetrics suffix, so the client
  strips it: `openpay.payments.created` reached Prometheus as `openpay_payments_total`, having
  quietly lost the word that said what it counted. It is `openpay.payments.accepted` now.

Every tag is drawn from a closed set — a status, a currency, a rule name. None is a merchant id or
a payment id: a label whose cardinality grows with traffic turns a time series database into an
outage. There is a `MeterFilter` ceiling on URI cardinality as a backstop, so that mistake would
lose one metric rather than the monitoring stack.

HTTP timings are published as histogram **buckets**, not client-side percentiles, because buckets
aggregate across instances and percentiles do not — averaging two instances' p99s gives a number
that is neither instance's p99 nor the fleet's.

### Logs

Promtail ships container logs to Loki with Docker service discovery, rather than the Loki logging
driver, which would have to be installed as a plugin on every machine before anything worked. Log
level becomes a label; the correlation id deliberately does not — it is unbounded, and a label per
request is how a small Loki runs out of memory. It stays in the line, where `|= "<id>"` finds it
just as well, and Grafana turns it into a link that pulls up every service's view of that one
request.

## The Audit Trail

Two questions the platform has to be able to answer months later: who was given the ability to move
money, and who tried to sign in and failed.

`audit_logs` is written by auth-service (logins, throttled attempts, key issuance, user creation)
and merchant-service (onboarding, webhook secret rotation). Two tables, not one shared audit
database — the same reasoning as everywhere else here: a service owns its schema, and a central
audit table would be the one outage that stops the whole platform recording anything. The code is
shared in `libs/common-audit`.

Three things about it are deliberate:

**Entries are written in their own transaction.** `REQUIRES_NEW`, so a record survives the rollback
of whatever it was recording. Without that, the most valuable entries — the refused login, the
rejected key issuance — would be written and then thrown away with the failing transaction, and the
log would contain only the actions that worked.

**Recording never breaks the thing it records.** A failed insert is logged at ERROR and swallowed.
The alternative turns an audit-table outage into a platform outage: nobody can sign in because the
record of them signing in cannot be written. The exposure is that someone who can already break
writes to this table can act unlogged, but that requires database access, at which point the audit
log was never the control holding them back.

**Nothing recorded is usable as a credential.** Key issuance stores the prefix, never the key.
Secret rotation stores that it happened, never the secret. An audit log holding live credentials
would be the softest place on the platform to steal one from.

The log is read on the **ops** tier rather than the admin tier, even though most entries are about
admin actions: investigating an incident should not require holding the credential that could cause
one. There is no write endpoint at all.

## Routing Rules

Which acquirer gets a payment lives in `provider_routing_rules`, not in `application.yml`. It used
to be configuration, which meant taking a misbehaving acquirer out of rotation required a
deployment — a slow answer to an acquirer having a bad afternoon, and the whole point of having two
is to be able to move.

A rule names a provider, a base URL, and a priority, and can be narrowed three ways: by merchant, by
currency, and by amount band. All three are nullable, and null means "no opinion" rather than
"never", so the general case — one rule per acquirer, applying to everything — is what a plain
deployment has.

**A merchant's own rules replace the general ones rather than merging with them.** Merging was the
obvious alternative and it is quietly wrong: an operator who pins one merchant to one acquirer
usually means *and not the other one*, and a merged list would fail over to exactly the acquirer
they were steering away from. The cost is that a merchant rule must name every acquirer that
merchant may use — which is the more honest thing to have to write down.

Disabling is not deleting, and it is scoped carefully:

- Disabling a rule stops **new payments** going to that acquirer.
- It does **not** stop refunds. A refund goes back to whoever holds the money, so `baseUrlFor`
  resolves disabled rules too. Otherwise taking an acquirer out of rotation would strand every
  refund against the payments it already took.
- Disabling a merchant's only override drops them back to the platform defaults rather than taking
  them offline. Switching a rule off should never be the thing that stops a merchant trading.

Amount bands are half-open — `[min, max)` — so `0–10000` and `10000–null` cover everything exactly
once, with no gap and no double-match at the boundary.

The table is seeded from `openpay.router.providers` the first time the service starts, so an
existing deployment comes up routing exactly as it did before. Only when the table is empty: after
that the table is the source of truth, and re-applying configuration on every boot would silently
undo an operator's decision to take an acquirer out of rotation — which is the single most likely
thing to be sitting in this table at 3am.

The table is read on every payment rather than cached. It is one indexed query against a handful of
rows, next to an HTTP call to a bank, and a cache would mean an operator taking an acquirer out of
rotation had to wait for a TTL to find out whether it had worked.

## Risk Screening

Every payment is screened before it is written, by a synchronous call to fraud-service. The gate
returns one of three answers and payment-service acts on each differently:

| Answer | What the merchant sees | What the platform does |
| --- | --- | --- |
| `ALLOW` | `201 Created` | Publishes `payment.created.v1`; routing proceeds |
| `REVIEW` | `201 Created`, `fraudStatus: HELD` | Persists the payment and publishes **nothing** |
| `BLOCK` | `422 payment_blocked` | Persists nothing at all |

A held payment is the interesting case. Withholding `payment.created.v1` is what stops it: routing
is driven entirely by that event, so a payment nobody has announced has not been offered to any
acquirer. When an operator closes the review, fraud-service publishes `fraud.check-completed.v1`,
payment-service consumes it, and the withheld event is published then — routing starts exactly as
it would have. A refused review fails the payment through the same transition path as any other
failure, so the ledger and the merchant's webhooks see a shape they already understand.

Releasing through an event rather than a callback is deliberate: an operator's decision survives
payment-service being down at the moment they click the button, and is retried until it lands.

### Rules are data

Rules live in `fraud_rules`, not in code. Risk thresholds are exactly the thing that needs changing
at 2am during a card-testing run, and a threshold you cannot change without a deployment is a
threshold you will not change.

Three rule types, each evaluable from one indexed query — the constraint that keeps the gate fast
enough to sit in the write path:

- `AMOUNT_OVER` — amount above a threshold, in minor units. Requires a currency: 5,000,000 paise
  and 5,000,000 cents are not the same policy, and the API refuses a rule that conflates them.
- `VELOCITY_COUNT` — more than N payments from one merchant inside a window.
- `REPEATED_AMOUNT` — more than N payments *of the same amount* inside a window. That is the card
  testing signature: the instrument varies, the amount does not.

Evaluation is first match in priority order, not most-severe-wins. Most-severe would make the
policy invisible — you could no longer tell what a payment would do without simulating every rule.
First match means the table reads top to bottom, and the cost of a bad ordering is a `BLOCK` rule
shadowed by a `REVIEW` rule, which `GET /internal/fraud/rules` shows you.

Rules are disabled, never deleted. A deleted rule takes with it the only explanation for every
decision that cites it, which is also why `fraud_decisions` stores the rule's name rather than a
foreign key to it.

### When screening is down

payment-service fails **open** by default, and records the payment as `UNSCREENED` rather than
`ALLOWED`. Failing closed would mean one unhealthy risk service stops every merchant on the
platform from taking money — an outage caused by the thing meant to prevent losses. Failing open is
a bounded, insurable cost, and the distinct status means the window is visible afterwards instead
of being indistinguishable from a clean pass. Set `FRAUD_FAIL_OPEN=false` to stop taking payments
instead.

### The review queue

`GET /internal/fraud/reviews` returns open reviews oldest first. Oldest first because a queue
worked newest-first starves its tail, and the payment at the tail is somebody's customer waiting at
a checkout. `openpay_fraud_open_reviews` is exported as a gauge for exactly that reason: the
question worth alerting on is how many are waiting right now.

Resolving a review records who did it, and keeps the original judgement alongside the resolution.
How a payment was first judged and what an operator decided about it are two different facts, and
overwriting the first with the second destroys the only record that a review ever happened.

## Settlement

A captured payment becomes payable immediately; money leaves on a schedule. Those are two separate
records on purpose, and keeping them apart is what makes a payout auditable back to the exact
payments inside it.

```text
payment CAPTURED
  └─> settlement_item accrued   gross 25000, fee 500 (2%), net 24500, PENDING
        └─> window closes
              └─> settlement    one per merchant, per currency, per date
                    gross 39999   fees 800   net 39199   3 items
```

Fees are 2% by default, taken in basis points so the arithmetic stays in integers. A flat fee is
supported but defaults to zero: with a non-zero one a small enough payment nets negative, which is
a real situation needing a carry-forward policy this phase does not implement. When it happens the
negative net is recorded and logged rather than clamped, because clamping would make the platform
silently absorb the shortfall and the books would stop reconciling.

Three rules are enforced:

- **One item per payment**, by unique constraint. A redelivered capture must not accrue the same
  money twice, and paying a merchant twice for one payment is the failure this prevents.
- **One settlement per merchant, currency, and date**, also by constraint. The run is safe to
  execute repeatedly; a second run finds nothing left to batch.
- **Eligible items are claimed with `FOR UPDATE SKIP LOCKED`**, so two concurrent runs cannot put
  the same item into two different payouts.

When a settlement is created it publishes `settlement.created.v1` through its own outbox, and the
ledger clears the payable:

```text
DEBIT   MERCHANT_PAYABLE   50000    we no longer owe it
CREDIT  PLATFORM_REVENUE    1000    the fee we kept
CREDIT  GATEWAY_CLEARING   49000    the cash that left
```

Gross equals fee plus net, so it balances. Without this the ledger would only ever grow: capture
credits the payable and nothing would debit it, so the books would report money owed to a merchant
who had already been paid.

A settlement's totals always equal the sum of its items, and `fee + net == gross` at both levels.
Verified end to end: two payments totalling 50000 accrued a payable of 50000, then settled to a
payout of gross 50000, fee 1000, net 49000 — after which the merchant's payable read exactly zero
and the fee appeared in platform revenue.

```bash
curl -X POST http://localhost:8087/internal/settlements/run -H "X-Admin-Token: dev-admin-token"
```

```bash
curl http://localhost:8080/api/v1/settlements/<SETTLEMENT_ID> -H "X-Api-Key: <API_KEY>"
```

## Merchant Webhooks

Merchants are told about outcomes rather than having to poll for them.

```text
payment CAPTURED / FAILED / REFUNDED, or refund SUCCEEDED
  └─> queued as one delivery per source event
        └─> POST to the merchant's URL, signed
              ├─ 2xx        DELIVERED
              └─ anything else, or a timeout
                    └─ retried with widening backoff, then ABANDONED
```

Not every internal state change is sent. `PENDING_PROVIDER` means we are mid-conversation with an
acquirer, which is our concern rather than the merchant's, and forwarding it would train them to
ignore us.

Each delivery carries three headers:

```text
X-OpenPay-Signature   HMAC-SHA256 of "<timestamp>.<body>"
X-OpenPay-Timestamp   unix seconds
X-OpenPay-Event-Id    stable per source event, so merchants can deduplicate
```

The timestamp is inside the signed payload deliberately. Signing the body alone would let anyone
who captured one delivery replay it forever, so a merchant should reject a stale timestamp as well
as a bad signature.

Each merchant gets a signing secret at onboarding, readable only through an admin-gated internal
endpoint and never returned by the merchant-facing read. Unlike an API key it cannot be stored as a
hash, because we have to reproduce the signature on every delivery; in a real deployment that
column belongs in a secret manager. It can be rotated without re-onboarding.

Failed deliveries are retried on exponential backoff up to a cap, then marked `ABANDONED` rather
than deleted: a merchant who never got told has to stay findable.

```bash
curl "http://localhost:8080/api/v1/webhooks/deliveries" -H "X-Api-Key: <API_KEY>"
```

## Event Delivery

The outbox relay claims rows with `FOR UPDATE SKIP LOCKED`, so running several replicas of
payment-service divides the work instead of publishing every event once per replica. Published
rows are purged after a retention window; `payment_events` remains the durable history.

A message a consumer cannot process goes to `<topic>.dlq.v1` after a few quick retries, carrying
the exception type, message, and stack trace in its headers. Spring Kafka's default is to retry
ten times and then drop the record, which in a payment system is the worst option available: the
event is gone, nothing alerts, and a payment simply stops advancing with no trace of why.

### Getting messages back out

Each consuming service exposes `/internal/dlq` on the ops token, covering the topics it consumes:

- `GET /internal/dlq/topics` — what this service can act on.
- `GET /internal/dlq?topic=&limit=` — what is waiting, **without** consuming it. Auto-commit is off
  precisely so that looking is free; a peek that quietly destroyed the queue it displayed would be
  worse than no tool.
- `POST /internal/dlq/replay?topic=&limit=` — re-publish to the original topic.
- `POST /internal/dlq/discard?topic=&limit=` — commit past messages without replaying them.

Replay publishes first and commits second, so a crash between the two replays a message twice
rather than losing it. That is the right way round here: every consumer is already idempotent for
exactly this reason, whereas a lost payment event is gone.

`topic` is checked against an allowlist per service. Replay publishes to a topic derived from the
request, so an endpoint that accepted any topic would let the ops token inject an arbitrary event
into the platform.

Discard is separate from replay on purpose. Replaying a message whose cause has not been fixed
sends it straight back to the DLQ at a new offset — the integration test asserts exactly that — so
using replay to clear a queue only moves the poison along. Giving up on a payment event should be a
deliberate act, and it is logged at WARN with the keys.

Nothing replays on a schedule. Automatic replay is how a poison message becomes an infinite loop,
and the decision to try again belongs to whoever knows what was changed.

## Testing

**420 automated tests** — 373 backend and 47 frontend — plus five k6 scenarios and a
fault-injection script that neither of those can replace. The layers exist because each one
catches something the layer below it structurally cannot see.

- **Unit tests** (`*Test`, surefire) — no infrastructure required.
- **Integration tests** (`*IT`, failsafe) — start a real PostgreSQL through Testcontainers, apply
  the Flyway migrations, and let Hibernate's `ddl-auto: validate` check the entities against the
  real schema. Several also start Mailpit or Kafka the same way to prove an email or a dead-letter
  path against the real thing, not a mock of it.
- **Dashboard tests** (`web/dashboard`, Vitest + Testing Library) — 47 tests aimed at the specific
  bugs a real audit of the frontend found: an out-of-order API response clobbering newer state, a
  payment detail drawer that could point a refund at the wrong payment, currency totals summed as
  raw integers across currencies that don't mix.

- **Shared-library tests** (`libs/`) — the outbox relay's ordering guarantee (it stops at the first
  failed send rather than publishing a capture ahead of its own authorisation), the audit
  recorder's promise that a failed audit write can never break the operation it was recording, and
  the event codec's forward-compatibility contract, without which every schema addition becomes a
  lockstep release across every service reading the topic.
- **Acceptance suite** (`scripts/e2e.sh`) — real HTTP against a running stack, covering the
  behaviour no unit test can see: filters, routing, tokens, and the asynchronous flow end to end.
- **Load and resilience** (`tests/performance/`, k6) — five scenarios: sustained write load, a
  ramp built specifically to find where the write path actually breaks (not just confirm a rate
  already known to be fine), a long steady-state soak for the failure modes only time reveals, a
  callback spike, and an acquirer taken out of rotation mid-run.
- **Fault injection** (`scripts/fault-injection.sh`) — not k6: pauses a real dependency (Redis,
  fraud-service, auth-service, Kafka) with `docker pause` and asserts that
  [docs/ARCHITECTURE.md § 5's failure-mode table](docs/ARCHITECTURE.md#5-failure-modes) is still
  true, rather than trusting that it is. Found a real bug on its first run — see
  [tests/performance/baseline.md](tests/performance/baseline.md#fault-injectionsh).

The integration tests exist because mocked-repository tests cannot see schema mismatches. A `jsonb`
column bound as `varchar`, and a `CHAR(3)` column mapped as varchar, both passed a fully green unit
suite and failed at runtime.

The load tests exist for the same reason one level up: `MetricsExposureIT` can prove a metric is
exported, but only a real run at 200 requests a second shows whether the outbox relay keeps up with
it. And the fault-injection script exists for the reason one level above *that*: a "Failure modes"
table with the right prose in it can still be describing a system that no longer behaves that way,
and the only way to know is to actually take the dependency down and watch.

If Testcontainers reports "Could not find a valid Docker environment" on a recent Docker Desktop,
the cause is API version negotiation, not a missing daemon. The root `pom.xml` pins
`testcontainers.docker.api.version`; override it with `-Dtestcontainers.docker.api.version=...`.

## Repository Layout

```text
docs/
  adrs/
  diagrams/
libs/
  common-observability/
  common-security/
  common-kafka/
  common-outbox/
platform/
  docker/
  observability/
  k8s/
scripts/
services/
tests/
  performance/
  gateway-service/
  auth-service/
  merchant-service/
  payment-service/
  webhook-service/
  provider-router-service/
  ledger-service/
  settlement-service/
  notification-service/
  fraud-service/
  mock-bank-service/
  demo-storefront/
web/
  dashboard/
```

- `services/` keeps deployable applications isolated.
- `libs/` holds cross-cutting code that multiple services share.
- `platform/` stores infrastructure assets instead of mixing them with app code:
  `docker/` for local Compose, `observability/` for Prometheus, Grafana, Loki and Promtail
  configuration, and `k8s/` for cluster manifests
  (see [platform/k8s/README.md](platform/k8s/README.md)).
- `web/` holds front-end applications, which are API clients rather than services.
- `scripts/` holds the local run script and the acceptance suite.
- `tests/performance/` holds the k6 load and resilience scenarios
  (see [tests/performance/README.md](tests/performance/README.md)).
- `docs/` captures architecture decisions, including
  [diagrams](docs/diagrams/) and [decision records](docs/adrs/).

## Status

Delivered:

- monorepo, Docker Compose infrastructure, Flyway migrations, actuator and Prometheus metrics
- correlation ID propagation
- merchant onboarding
- API key issuance and validation, with admin-gated issuance
- gateway routing and API key enforcement
- payment creation with fingerprinted idempotency, plus the payment state machine
- transactional outbox and a Kafka event backbone
- provider routing with failover and a per-acquirer circuit breaker
- simulated acquirers with configurable latency, declines, and outages
- signature-verified, deduplicated provider callbacks
- double-entry ledger with an append-only journal enforced in the database
- dead-letter routing for unprocessable events, and a multi-replica-safe outbox relay
- settlement accrual, fee calculation, and payout batching that reconciles against the ledger
- refunds with over-refund protection and negative-balance carry-forward
- signed outbound merchant webhooks with retries and a delivery log
- the entire platform containerised and runnable with one command
- human login with BCrypt password hashing and short-lived HS256 sessions, accepted anywhere an API
  key is, backed by rotating refresh tokens with theft detection (reusing a spent refresh token
  revokes every session for that user) and silent renewal in the dashboard
- a merchant dashboard: sign in, watch payments settle, and issue refunds
- payment methods captured without keeping anything worth stealing, and per-payment acquirer
  attempt history
- credential authority actually enforced: a read-only key or viewer session can read but not move
  money (see [docs/SECURITY-AUDIT.md](docs/SECURITY-AUDIT.md))
- merchant-facing settlements and webhook delivery history, split from the operator views
- CI running unit and integration tests on JDK 21 and 25, plus dashboard build, manifest
  validation, container images to GHCR, and a nightly end-to-end acceptance run
  (see [docs/ci-cd.md](docs/ci-cd.md))
- rule-based risk screening in the payment write path, with a review queue and a release that
  survives payment-service being down
- an audit trail that survives the transaction it is recording, and stores nothing usable as a
  credential
- dead-letter inspection and replay, so a stuck message is an operator action rather than a console
  producer and a guess
- routing rules in a table, so taking an acquirer out of rotation is a request rather than a
  deployment
- provisioned Grafana dashboards over real business metrics, container logs in Loki, and a test
  that fails when a metric a dashboard queries stops being exported
- Kubernetes manifests: probes split three ways, network policy that default-denies, and an
  ingress that publishes three hosts and hides every operator surface — deployed to a real
  cluster and taken through a full login-and-payment flow, not just validated as YAML (see
  [platform/k8s/README.md](platform/k8s/README.md#verified-against-a-real-cluster))
- k6 load and resilience scenarios, including one that disables an acquirer mid-run and asserts
  that acceptance does not move
- email notifications: a security alert to the account holder when refresh-token theft is
  detected, and an ops alert when a merchant's webhook delivery is abandoned — both against a real
  SMTP send, caught locally by Mailpit rather than a real provider

Every roadmap phase is implemented. What remains open is deliberate scope rather than unfinished
work — see [docs/roadmap.md](docs/roadmap.md):

- **no payout rail.** Settlement batches what a merchant is owed and clears the payable in the
  ledger, and then nothing sends money anywhere. Both acquirers are simulated, so no funds ever
  leave a database.
- **no distributed tracing.** Correlation IDs and Loki instead, which answers *what happened* well
  and *where the time went* only coarsely — see
  [ADR-0010](docs/adrs/0010-correlation-id-not-tracing.md).

## Limitations and what I'd do next

Split by the only distinction that matters: things that are a decision, and things that are a
constraint. Being able to tell them apart is most of engineering judgement, so they are labelled
rather than blended into one list of caveats.

### Can't be fixed here — they need something this project doesn't have

| Limitation | Why it can't be fixed | What it would take |
| --- | --- | --- |
| **No real money moves.** Both acquirers are simulated and settlement clears a payable without sending funds anywhere. | Real acquiring needs a licensed entity, a bank relationship, and PCI-DSS scope. Not obtainable for a portfolio project. | A real acquirer sandbox (Razorpay/Stripe/Adyen) behind the existing `ProviderClient` interface — the routing, failover, and callback verification are already written against that seam, so it is an adapter, not a rewrite. |
| **No PCI compliance.** Card data is captured as a network + last four and nothing else. | Compliance is an audited organisational process, not a code property. | Keep the current design (store nothing worth stealing) and put a real tokenisation vault in front. The data model already assumes it. |
| **Throughput numbers are laptop numbers.** At least 50 payments/second clean on one host running all 22 containers; above that, run-to-run variance on this hardware exceeds the signal, so no ceiling is claimed. | Twelve JVMs, Postgres, Kafka, and Redis are sharing 12 vCPUs. The ceiling measured is the host's, not the architecture's. | Run the same k6 scripts against the existing Kubernetes manifests on real nodes. Nothing in the code changes; only the number does. |
| **Fraud rules are a rule engine, not a model.** Threshold, velocity, and repeated-amount rules. | A real risk model needs labelled fraud data that doesn't exist for synthetic traffic. | Keep the rules as the fast deterministic path and add a scored model behind the same `FraudDecision` interface. |

### Can be fixed — I know how, it's scope not skill

Roughly in the order I'd actually do them:

1. **No distributed tracing.** Correlation IDs answer *what happened* but not *where the time
   went*. Given how much of the latency work below came down to guessing which hop was slow and
   then proving it by elimination, this is the thing I'd most want next.
   **Fix:** OpenTelemetry spans across the gateway → payment → fraud hop.
   ([ADR-0010](docs/adrs/0010-correlation-id-not-tracing.md) explains why it was deferred.)
2. **The soak test is four minutes, not four hours.** Long enough to prove the mechanism, far too
   short to trust as evidence about a slow leak. **Fix:** `-e DURATION=4h` and actually watch it.
3. **No chaos under concurrent load.** `fault-injection.sh` proves fail-open and recovery, but one
   dependency at a time with no traffic. The interesting case — Redis dying *during* a 200/s run —
   isn't covered by anything yet. **This is no longer hypothetical:** the one genuinely nasty
   failure found in this codebase was a Kafka broker that stayed up, stayed healthy, and quietly
   stopped committing consumer offsets under a backlog, silently freezing every payment at
   `CREATED` with no error anywhere. `fault-injection.sh` cannot find that class of bug by
   construction — it tests clean failures, and this was a degraded one under load. Written up in
   [ARCHITECTURE.md § 5.1](docs/ARCHITECTURE.md#51-the-failure-this-table-did-not-have).
4. **No consumer-contract tests.** Services agree on JSON payload shapes in `common-kafka`, and
   `EventCodecTest` pins forward-compatibility, but nothing fails when a producer removes a field a
   consumer still reads. **Fix:** Pact, or schema-registry-backed contracts.
5. **No coverage threshold.** JaCoCo now measures both suites and merges them into one report per
   module, but nothing fails a build for dropping. A number picked before there was a baseline
   would either pass everything or fail the first honest measurement, so the gate waits until
   there is something real to set it against.

### What I'd tell a reviewer to look at first

Not the feature list — the parts where the reasoning is visible:

- [`docs/adrs/`](docs/adrs/) — ten decisions, each naming the alternative it rejected and why.
- [`tests/performance/baseline.md`](tests/performance/baseline.md) — includes a hypothesis I
  recorded, tested, and found wrong, left in the document with the correction rather than quietly
  overwritten.
- [`scripts/fault-injection.sh`](scripts/fault-injection.sh) — the failure-mode table checked
  against reality instead of trusted, which is what found the Redis bug.

## Documentation

| | |
| --- | --- |
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | What every component is, what happens on each request, and what breaks when a piece goes down |
| [diagrams/](docs/diagrams/) | System context, service topology, both halves of the payment flow, state machine, data model, event flow, deployment |
| [adrs/](docs/adrs/) | Ten decisions, each naming the alternative and why it lost |
| [SECURITY-AUDIT.md](docs/SECURITY-AUDIT.md) | Twelve findings, eleven fixed and one accepted, plus a review of everything added since |
| [runbook.md](docs/runbook.md) | What to do when something is wrong, starting with the failures that are silent |
| [release-checklist.md](docs/release-checklist.md) | Short enough to actually read |
| [demo-script.md](docs/demo-script.md) | Fifteen minutes, showing the interesting parts rather than the easy ones |
| [ci-cd.md](docs/ci-cd.md) | What the pipeline gates, and what it deliberately does not |
| [roadmap.md](docs/roadmap.md) | Every phase, and what was actually built in each |
