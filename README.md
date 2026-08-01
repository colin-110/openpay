# OpenPay

OpenPay is a payment gateway portfolio project built to demonstrate realistic backend
architecture, distributed systems patterns, and production engineering practices.

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
| `mock-bank-service` | 9001 / 9002 | — | Simulated acquirers. One codebase, run twice as `mock-bank-a` and `mock-bank-b`. |

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
  client-supplied header.
- **Dashboard session** (`Authorization: Bearer`) — for people. A short-lived HS256 JWT issued by
  `POST /api/v1/auth/login`, accepted on exactly the same paths as an API key. Downstream code
  never learns which of the two was used: a payment read is scoped to a merchant either way.
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
$env:OPENPAY_ADMIN_TOKEN = "dev-admin-token"; $env:OPENPAY_OPS_TOKEN = "dev-ops-token"; $env:OPENPAY_INTERNAL_TOKEN = "dev-internal-token"; $env:OPENPAY_JWT_SECRET = "dev-jwt-secret-not-for-production-use"; $env:OPENPAY_DASHBOARD_ORIGINS = "http://localhost:5173"
```

bash:

```bash
export OPENPAY_ADMIN_TOKEN=dev-admin-token OPENPAY_OPS_TOKEN=dev-ops-token OPENPAY_INTERNAL_TOKEN=dev-internal-token OPENPAY_JWT_SECRET=dev-jwt-secret-not-for-production-use OPENPAY_DASHBOARD_ORIGINS=http://localhost:5173
```

Every service gets the same signing key: auth-service issues sessions and the services behind the
gateway verify them. `scripts/run-local.ps1` sets all of these for you.

### 3. Build

```bash
./mvnw clean verify
```

`test` runs the unit tests. `verify` additionally runs the `*IT` integration tests, which start a
real PostgreSQL via Testcontainers and require Docker to be running.

### 4. Run everything in Docker

The whole platform, infrastructure and services, from one command:

```bash
docker compose -f platform/docker/docker-compose.yml -f platform/docker/docker-compose.apps.yml up -d --build
```

Services start in dependency order and wait on each other's health checks, so the first run takes a
few minutes and then everything is up. Tear it down with:

```bash
docker compose -f platform/docker/docker-compose.yml -f platform/docker/docker-compose.apps.yml down
```

One Dockerfile builds every service. The build stage is identical for all of them, so Docker builds
the reactor once and each image reuses that layer; only the final `COPY` differs. Images run as a
non-root user and size their heap from the container's memory limit rather than a hard-coded
`-Xmx`.

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

Three sections:

- **Overview** — captured volume, success rate, refunded value, and how many payments are still
  with an acquirer, over the most recent window of traffic. Figures state the window they cover
  rather than implying they cover everything.
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
a link somebody can send.

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

That prints the login it created. Then run the dashboard:

```bash
cd web/dashboard && npm install && npm run dev
```

It serves on `http://localhost:5173` and talks to the gateway on 8080 and auth-service on 8081.
Point it elsewhere with `VITE_API_BASE` and `VITE_AUTH_BASE`.

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

Human-facing, on auth-service directly, unauthenticated by necessity:

- `POST /api/v1/auth/login` — returns a session token. An unknown email and a wrong password fail
  identically, so login cannot be used to discover who has an account.

Internal, not exposed through the gateway:

- `POST /api/v1/auth/validate-key` — called by the gateway and payment-service.
- `POST /internal/provider/webhooks/{provider}` — acquirer callbacks. HMAC-signed over
  `timestamp.body` with `X-Provider-Signature` and `X-Provider-Timestamp`, refused outside a
  five-minute window, and deduplicated on the provider's own event id. The timestamp is inside the
  signature, so it cannot be rewritten to make a captured callback look fresh.
- `GET /internal/router/providers` — circuit breaker state per acquirer.
- `GET /internal/router/payments/{paymentId}/attempts` — what was tried, in order, and why each
  attempt ended.
- `GET /api/v1/ledger/accounts/{accountCode}/balance` — derived from the journal, admin-gated.
- `GET /api/v1/ledger/entries?referenceId={paymentId}` — every transaction and both sides of each.
Operator reporting and administration, authenticated with `X-Ops-Token`:

- `GET /internal/settlements` — every merchant's payouts.
- `POST /internal/settlements/run` — close a settlement window explicitly.
- `POST /internal/settlements/{settlementId}/complete` — mark a payout paid.
- `POST /api/v1/merchants/{merchantId}/webhook-secret` — rotate the signing secret.
- `GET /internal/webhooks/deliveries?merchantId=` — delivery history across merchants.
- `GET /api/v1/ledger/entries?referenceId={paymentId}` — every transaction and both sides of each.
- `GET /api/v1/ledger/accounts/{accountCode}/balance` — derived from the journal.
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

- **Unit tests** (`*Test`, surefire) — no infrastructure required.
- **Integration tests** (`*IT`, failsafe) — start a real PostgreSQL through Testcontainers, apply
  the Flyway migrations, and let Hibernate's `ddl-auto: validate` check the entities against the
  real schema.

The integration tests exist because mocked-repository tests cannot see schema mismatches. A `jsonb`
column bound as `varchar`, and a `CHAR(3)` column mapped as varchar, both passed a fully green unit
suite and failed at runtime.

If Testcontainers reports "Could not find a valid Docker environment" on a recent Docker Desktop,
the cause is API version negotiation, not a missing daemon. The root `pom.xml` pins
`testcontainers.docker.api.version`; override it with `-Dtestcontainers.docker.api.version=...`.

## Repository Layout

```text
docs/
libs/
  common-observability/
  common-security/
  common-kafka/
  common-outbox/
platform/
  docker/
  observability/
scripts/
services/
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
web/
  dashboard/
```

- `services/` keeps deployable applications isolated.
- `libs/` holds cross-cutting code that multiple services share.
- `platform/` stores infrastructure assets instead of mixing them with app code.
- `web/` holds front-end applications, which are API clients rather than services.
- `scripts/` holds the local run script and the acceptance suite.
- `docs/` captures architecture decisions.

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
- human login with BCrypt password hashing and HS256 sessions, accepted anywhere an API key is
- a merchant dashboard: sign in, watch payments settle, and issue refunds
- payment methods captured without keeping anything worth stealing, and per-payment acquirer
  attempt history
- credential authority actually enforced: a read-only key or viewer session can read but not move
  money (see [docs/SECURITY-AUDIT.md](docs/SECURITY-AUDIT.md))
- merchant-facing settlements and webhook delivery history, split from the operator views
- CI running unit and integration tests on JDK 21 and 25
- rule-based risk screening in the payment write path, with a review queue and a release that
  survives payment-service being down
- an audit trail that survives the transaction it is recording, and stores nothing usable as a
  credential
- dead-letter inspection and replay, so a stuck message is an operator action rather than a console
  producer and a guess

Not yet built (see [docs/roadmap.md](docs/roadmap.md)):

- refresh tokens: a session simply expires and you sign in again
- Kubernetes manifests
- any real money movement: the acquirers are simulated, so nothing leaves a database
