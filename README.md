# OpenPay

A payment gateway, built the way a real one has to work: thirteen services, one Kafka event
backbone, a double-entry ledger a database trigger enforces, and money handled as integer minor
units end to end. Every payment goes through tokenisation, idempotency, risk screening, provider
failover, and a transactional outbox — not because a demo needs it, but because a system that moves
money doesn't get to skip any of them.

**See it without installing anything.** One click opens the whole platform in a browser — twenty-two
containers, the shop, and the merchant dashboard, running on GitHub's machine rather than yours:

[![Open in GitHub Codespaces](https://github.com/codespaces/badge.svg)](https://codespaces.new/colin-110/openpay)

First build takes about ten minutes, and the shop's URL is printed when it finishes.

**Or in one command locally**, if you have Docker. A shop, a real card number, and a payment that
reaches captured on its own — then the same payment from the merchant's side:

```bash
./scripts/demo.sh
```

Then open **http://localhost:8090**. Nothing to configure — credentials are generated on first run,
and the demo merchant, its API keys and a dashboard login are minted at startup, with the shop
printing the login on its own page.

### Then break it on purpose

A payment succeeding proves very little; almost anything succeeds on the happy path. The claim
worth checking is what happens when the bank goes down *during* a payment, and that one cannot be
shown by looking at a working system — a working system is exactly what it looks like. So break it:

```bash
./scripts/demo-failover.sh
```

It stops an acquirer, takes a real payment while it is down, and prints which bank refused and
which one took it. Verbatim output from a run of the command above:

```
Stopping mock-bank-a...
mock-bank-a is down. One acquirer left.

Card tokenised: tok_77cmO8QwJ3MN...
Payment created: c9e3cb07-d656-4dcf-97e7-a900ac995931

Waiting for capture .........

  STATUS: CAPTURED — with mock-bank-a down for the whole payment.

  Attempts:
    1   mock-bank-a    FAILED     -
    2   mock-bank-b    ACCEPTED   mock-bank-b-17ba9c3f-6a0b-40d6-8d75-a895d238bbc1

Restoring mock-bank-a...
```

The failed attempt is *kept*, not tidied away. A platform that forgets which acquirer refused
cannot reconcile against that acquirer's settlement file, and cannot answer a merchant asking why
a payment took nine seconds. The same two rows appear in the dashboard against the payment, with
the timeline beside them.

The acquirer is restored on the way out, including if the script fails or is interrupted.

- [The problem this solves](#the-problem-this-solves) — the six ways taking a payment goes wrong
- [Measured performance](#measured-performance) — real k6 runs, including the one that corrected an earlier claim
- [Architecture](docs/ARCHITECTURE.md) — what every component is, and what breaks when one dies
- [Reference](docs/REFERENCE.md) — every endpoint and mechanism in detail
- [Limitations](#limitations-and-what-id-do-next) — what's missing, and what I'd fix first

## The problem this solves

Taking a payment is easy. Taking a payment *correctly* is not, and almost none of the difficulty
is in the happy path. The hard parts are the ways it goes wrong:

| The failure | What it costs | What this platform does about it |
| --- | --- | --- |
| A customer taps "Pay" twice, or the network retries a request that already succeeded | The card is charged twice, and someone has to find and refund it | [Idempotency](docs/REFERENCE.md#idempotency) keyed on the request *and* its body, enforced by a unique index — a retry returns the original payment, a different body under a reused key is refused |
| The acquiring bank goes down mid-flight | Payments are refused while the merchant is open for business | [Provider failover](docs/REFERENCE.md#routing-rules) with a per-acquirer circuit breaker. Measured: **100% of payments still accepted** with an acquirer pulled out of rotation mid-run |
| The service crashes between saving a payment and announcing it | The payment exists but nothing downstream ever hears about it — no capture, no settlement, no ledger entry | A [transactional outbox](docs/REFERENCE.md#event-delivery): the row and the event commit in one transaction, and a relay publishes afterwards. A crash replays; it does not lose |
| The books stop balancing | Nobody can say which payment is wrong, and every reconciliation after it is suspect | A [double-entry ledger](docs/REFERENCE.md#the-ledger) where both *append-only* and *debits equal credits* are enforced by database triggers, not by application code a future bug can bypass |
| An attacker replays a captured "payment succeeded" callback from the bank | Goods ship for a payment that never settled | [Signature verification](docs/REFERENCE.md#merchant-webhooks) over `timestamp.body`, so a captured callback expires instead of staying valid forever, plus deduplication on the provider's own event id |
| A merchant's server is compromised, or its checkout page is read by a visitor | Every card it has ever taken is exposed | The card goes from the browser to [vault-service](#the-vault) directly and comes back as a single-use token. The merchant's server never sees a card number and could not leak one |

Every one of those is a claim that can be checked rather than believed, and
[Measured performance](#measured-performance) is where the checking is written down — including
the runs that proved an earlier claim wrong.

## Services

| Service | Port | Owns | Responsibility |
| --- | --- | --- | --- |
| `gateway-service` | 8080 | — | Front door. Authenticates merchant credentials, rate limits, and routes to the service that owns each path. |
| `auth-service` | 8081 | `openpay_auth` | Issues and validates API keys. Stores only key hashes. |
| `merchant-service` | 8082 | `openpay_merchant` | Merchant onboarding and lookup. |
| `payment-service` | 8083 | `openpay_payment` | Payment creation, idempotency, the state machine, and the transactional outbox. |
| `webhook-service` | 8084 | `openpay_webhook` | Trust boundary for inbound provider callbacks: signature verification and deduplication. |
| `provider-router-service` | 8085 | `openpay_router` | Chooses an acquirer, fails over, and trips a circuit breaker on a bad one. |
| `ledger-service` | 8086 | `openpay_ledger` | Double-entry journal. Append-only and balanced, enforced by the database. |
| `settlement-service` | 8087 | `openpay_settlement` | Accrues payables on capture and batches them into merchant payouts. |
| `notification-service` | 8088 | `openpay_notification` | Delivers signed webhooks to merchants, with retries and a delivery log. |
| `fraud-service` | 8089 | `openpay_fraud` | Screens payments against rules held in a table, and owns the review queue. |
| `vault-service` | 8091 | — (Redis only) | Turns a card into a single-use token. The only service that sees a card number, and the only one with no database. |
| `mock-bank-service` | 9001 / 9002 | — | Simulated acquirers. One codebase, run twice as `mock-bank-a` and `mock-bank-b`. |
| `demo-storefront` | 8090 | — | A shop that does not exist, taking payments that really happen. Holds its secret key server-side, exactly as a merchant integration must. |

Shared code lives in `libs/`: `common-observability` (correlation IDs), `common-security`
(authentication and the authority model), `common-kafka` (topic names, the event envelope, and the
JSON contracts every service agrees on), `common-outbox` (the transactional outbox), `common-audit`
(the audit trail), and `common-email`.

## The vault

The one part worth reading the code for, because it is where the architecture is least obvious.

A card number is the only piece of data here worth stealing on its own, and the answer is not to
guard it better but to **hold it in fewer places**. So the browser posts the card straight to
`vault-service` — never to the merchant's server — and gets back a token. Consequences:

- **No database.** Tokens live in Redis and expire on their own. A table would need a reaper nobody
  would notice failing, turning a fifteen-minute secret into a permanent one.
- **Single use, atomically.** Redemption is `getAndDelete`, so two concurrent redemptions of one
  token cannot both succeed.
- **The PAN is never retained at all** — a deliberate departure from what a real vault does. A real
  acquirer keeps it because something downstream presents it to a card network. Nothing here ever
  does. Holding the one secret worth stealing to satisfy a reader that does not exist is a worse
  position, not a more realistic one.
- **The token is the authority on the payment method.** payment-service redeems it, so the network
  and last four on a payment are what was *actually* tokenised, not what the merchant claimed.

## Credentials

Six kinds of credential, and exactly one of them is deliberately public:

- **Merchant API key** (`X-Api-Key`) — for payment traffic. A secret; must stay on a server.
- **Publishable key** (`X-Api-Key`, prefix `opk_pub_`) — the only credential meant to be *read by
  strangers*. It sits in a checkout page where anyone can lift it, and carries scope
  `tokens:create`: it may exchange a card for a token and is refused by every read and write path on
  the platform. A distinct prefix so a key in a log answers "how bad is this?" without a lookup.
- **Dashboard session** (`Authorization: Bearer`) — a 15-minute HS256 JWT for people, accepted on
  exactly the same paths as an API key.
- **Admin token** (`X-Admin-Token`) — for actions that create a business identity or a credential.
- **Ops token** (`X-Ops-Token`) — for operator reporting that mints nothing.
- **Service token** (`X-Internal-Token`) — for service-to-service calls.

The three operator tiers are separate secrets on purpose, split by *does this mint another
credential* rather than by *does this feel sensitive*. Full detail in
[ARCHITECTURE.md § Trust boundaries](docs/ARCHITECTURE.md#2-trust-boundaries).

## Getting Started

The one-command form is at the top of this file. Alternatives — running services from Maven,
running them individually, local TLS, and watching failover happen by hand — are in
[REFERENCE.md § Running it other ways](docs/REFERENCE.md#running-it-other-ways).

| | |
| --- | --- |
| Shop | `http://localhost:8090` |
| Dashboard | `http://localhost:5173` |
| API (via the gateway) | `http://localhost:8080` |
| Grafana | `http://localhost:3000` |
| Prometheus | `http://localhost:9090` |

Tear it down with `down`, or `down -v` to also drop the volumes for a genuinely clean slate.

## Measured performance

Numbers, not adjectives — and where a number turned out to be wrong, the correction is here rather
than the inconvenient run quietly deleted. Raw output and full caveats in
[tests/performance/baseline.md](tests/performance/baseline.md).

**Where it was measured:** one Windows 10 laptop, Docker Desktop with 12 vCPUs / 7.4 GiB, running
*all 22 containers at once* — every service, Postgres, Kafka, Redis, Prometheus, Grafana, Loki, and
the load generator. A deliberately hostile setup for a throughput number, stated plainly because a
benchmark without its hardware means nothing.

### Write throughput

**At least 50 payments a second, sustained, with zero failures — and this host cannot honestly say
more than that.**

That figure is lower than an earlier version of this README claimed, and the reason is the
interesting part. The first runs drove a single merchant, so the seeded `merchant-velocity-burst`
rule held most payments for review. A held payment is deliberately *not* announced for routing — no
acquirer, no ledger, no settlement — and it still returns 201, so k6 counted it as a success. Those
runs measured roughly a tenth of the work a payment actually does.

Re-measured across a pool of merchants with every payment doing the whole job, three runs of the
identical script:

| Tier | Run A | Run B | Run C |
| --- | --- | --- | --- |
| 20/s | 0.0% | 0.0% | 0.0% |
| 50/s | 0.0% | 0.0% | 0.0% |
| 100/s | 10.0% | 0.0% | 9.7% |
| 150/s | 41.4% | 4.6% | 0.0% |

Above 50/s the tiers invert between runs — Run C passed 150/s cleanly while failing 100/s — which
is the measurement being swamped by a host running twelve JVMs, three datastores and the load
generator on twelve shared vCPUs. A ceiling needs hardware that is not also the system under test.

### Resilience — the claims, tested

These are the interesting numbers, because they are what a payment platform is actually judged on.
Each was produced by breaking something real and watching.

| What was done to it | Result |
| --- | --- |
| An acquirer pulled out of rotation mid-run (`provider-outage.js`) | **100.00% of payments still accepted** (2,401 / 2,401), p95 58ms — losing an acquirer cost nothing, not even latency |
| Callback traffic spiked 40× (10/s → 400/s, `webhook-spike.js`) | 35,515 callbacks, 0.00% errors, and **3,411 duplicate callbacks all correctly rejected** — no double-credit under load |
| Held at 25/s for 4 minutes (`soak.js`) | p95 90ms in the first half, **82ms in the second** — no drift, no leak, 0 failures |
| Redis paused (`fault-injection.sh`) | Rate limiting and login throttling fail open — **after a fix; this is where the Lettuce timeout bug was found** |
| fraud-service paused | Screening fails open, payment recorded `UNSCREENED` rather than refused |
| auth-service paused | API-key payments refused (503); existing dashboard sessions keep working |
| Kafka paused, then restored | Payment creation still succeeds; the payment advances on its own when Kafka returns — **nothing lost** |

### The bug none of the load tests could have found

Every script above drives sustained load, and load is precisely the condition under which this
could not happen. It took reading the payments table of a stack that had merely been left alone:

| Gap before the payment | `fraud_status` |
| --- | --- |
| 33s after start | `UNSCREENED` |
| 40s (traffic flowing) | `ALLOWED` |
| 2m 24s idle | `UNSCREENED` |
| seconds apart | `ALLOWED` ×6 |
| 3h 02m idle | `UNSCREENED` |

The first payment after any idle gap skipped risk screening entirely. Screening answers in ~35ms
warm, goes cold after roughly two minutes of quiet, and overruns the caller's 1s timeout — which
**fails open**, so the result is not an error but a captured payment recorded as never checked.
Fixed by running the warm-up on a timer, and verified the way it was found: four separate 200s+
idle windows, all `ALLOWED`, zero timeouts.

A performance suite that only measures a busy system is blind to every failure that needs quiet.

## Testing

| Suite | Count |
| --- | --- |
| Backend unit tests (surefire) | **345** across 49 classes |
| Backend integration tests (failsafe, Testcontainers) | real PostgreSQL and Kafka per module |
| Dashboard (vitest) | **50** |
| End-to-end acceptance (`scripts/e2e.sh`) | 96 checks against a running stack |
| Tokenisation end-to-end (`scripts/verify-tokenization.sh`) | 7 checks, including that a refusal never quotes the card number |

`mvn verify`, not `mvn test` — the integration suite only runs under the former, and treating a
green unit run as sufficient is how a real bug reached CI during this project's own development.

Full detail, including what each suite deliberately does *not* cover, in
[docs/TESTING.md](docs/TESTING.md).

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
- card tokenisation in a service of its own, holding no database and never retaining a card number,
  with a publishable key that is safe to put in a page because it can do nothing else — and the
  token, not the merchant's claim, deciding what instrument a payment carries
- a storefront with a real catalogue and basket, priced on the server so an amount sent by the
  browser is ignored rather than trusted
- a demo that provisions itself: one command mints the merchant, both keys and a dashboard login,
  with the admin token held by a container that exits rather than by the shop
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
| [REFERENCE.md](docs/REFERENCE.md) | Every endpoint and mechanism in detail, and the other ways to run it |
| [DEPLOY.md](docs/DEPLOY.md) | Putting it on a real host, and putting the shop on a second one |
| [DEMO.md](docs/DEMO.md) | A ten-minute walkthrough that opens with a shop rather than a terminal |
| [TESTING.md](docs/TESTING.md) | What each suite covers, and what it deliberately does not |
| [diagrams/](docs/diagrams/) | System context, service topology, both halves of the payment flow, state machine, data model, event flow, deployment |
| [adrs/](docs/adrs/) | Ten decisions, each naming the alternative and why it lost |
| [SECURITY-AUDIT.md](docs/SECURITY-AUDIT.md) | Twelve findings, eleven fixed and one accepted, plus a review of everything added since |
| [runbook.md](docs/runbook.md) | What to do when something is wrong, starting with the failures that are silent |
| [release-checklist.md](docs/release-checklist.md) | Short enough to actually read |
| [demo-script.md](docs/demo-script.md) | Fifteen minutes, showing the interesting parts rather than the easy ones |
| [ci-cd.md](docs/ci-cd.md) | What the pipeline gates, and what it deliberately does not |
| [roadmap.md](docs/roadmap.md) | Every phase, and what was actually built in each |
