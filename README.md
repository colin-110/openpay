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
| `mock-bank-service` | 9001 / 9002 | — | Simulated acquirers. One codebase, run twice as `mock-bank-a` and `mock-bank-b`. |

Shared code lives in `libs/`: `common-observability` (correlation IDs), `common-security`
(API key and admin token authentication, applied per path by configuration), and `common-kafka`
(topic names, the event envelope, and the JSON event contracts every service agrees on).

## Credentials

Two kinds of caller, two kinds of credential:

- **Merchant API key** (`X-Api-Key`) — for payment traffic. Issued by auth-service, presented by
  merchants. Merchant identity is derived from the validated key and never read from a
  client-supplied header.
- **Admin token** (`X-Admin-Token`) — for platform-operator actions: onboarding merchants and
  issuing API keys.

The admin token has **no default value**. A shipped default would be a publicly known secret, so
admin endpoints fail closed until `OPENPAY_ADMIN_TOKEN` is set. Services log a warning at startup
when it is missing.

## Getting Started

### 1. Start infrastructure

```bash
docker compose -f platform/docker/docker-compose.yml up -d
```

### 2. Set the admin token

PowerShell:

```bash
$env:OPENPAY_ADMIN_TOKEN = "dev-admin-token"
```

bash:

```bash
export OPENPAY_ADMIN_TOKEN=dev-admin-token
```

### 3. Build

```bash
./mvnw clean verify
```

`test` runs the unit tests. `verify` additionally runs the `*IT` integration tests, which start a
real PostgreSQL via Testcontainers and require Docker to be running.

### 4. Run everything at once (Windows)

Ten processes in ten terminals is enough friction to stop anyone actually running this, so
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

### 5. Or run the services individually

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

The two acquirers are the same module run twice with different configuration:

```bash
BANK_NAME=mock-bank-a BANK_PORT=9001 BANK_SIGNING_SECRET=bank-a-secret ./mvnw -pl services/mock-bank-service -am spring-boot:run
```

```bash
BANK_NAME=mock-bank-b BANK_PORT=9002 BANK_SIGNING_SECRET=bank-b-secret ./mvnw -pl services/mock-bank-service -am spring-boot:run
```

Start order matters for a full flow: auth-service verifies merchants against merchant-service, and
payment-service verifies API keys against auth-service.

### 6. Watch failover happen

Kill `mock-bank-a` and create a payment. The router records a failed attempt against A, succeeds on
B, and after three consecutive failures stops calling A at all:

```bash
curl http://localhost:8085/internal/router/providers
```

```bash
curl http://localhost:8085/internal/router/payments/<PAYMENT_ID>/attempts
```

Each acquirer can also be made to misbehave on purpose with `BANK_DECLINE_RATE`,
`BANK_TIMEOUT_RATE`, and `BANK_UNAVAILABLE`.

## API Walkthrough

Onboard a merchant (admin):

```bash
curl -X POST http://localhost:8082/api/v1/merchants -H "X-Admin-Token: dev-admin-token" -H "Content-Type: application/json" -d '{"merchantCode":"shop-1","legalName":"Demo Shop","webhookUrl":null,"defaultCurrency":"USD"}'
```

Issue an API key for that merchant (admin). The plaintext key is returned exactly once:

```bash
curl -X POST http://localhost:8081/api/v1/api-keys -H "X-Admin-Token: dev-admin-token" -H "Content-Type: application/json" -d '{"merchantId":"<MERCHANT_ID>","name":"primary","scope":"payments:write","expiresAt":null}'
```

Create a payment through the gateway:

```bash
curl -X POST http://localhost:8080/api/v1/payments -H "X-Api-Key: <API_KEY>" -H "Idempotency-Key: order-1001" -H "Content-Type: application/json" -d '{"amount":10000,"currency":"USD"}'
```

That returns `201` with status `CREATED`. Nothing else is required: poll the payment and watch it
advance on its own as the router dispatches it and the acquirer calls back.

```bash
curl http://localhost:8080/api/v1/payments/<PAYMENT_ID> -H "X-Api-Key: <API_KEY>"
```

## Endpoints

Merchant-facing, via the gateway on 8080, authenticated with `X-Api-Key`:

- `POST /api/v1/payments` — create a payment. Requires `Idempotency-Key`.
- `GET /api/v1/payments/{paymentId}`
- `GET /api/v1/payments?page=0&size=20`

Platform-operator, authenticated with `X-Admin-Token`:

- `POST /api/v1/merchants`
- `GET /api/v1/merchants/{merchantId}`
- `GET /api/v1/merchants?page=0&size=20`
- `POST /api/v1/api-keys`

Internal, not exposed through the gateway:

- `POST /api/v1/auth/validate-key` — called by the gateway and payment-service.
- `POST /internal/provider/webhooks/{provider}` — acquirer callbacks. HMAC-signed over the raw
  request body and deduplicated on the provider's own event id.
- `GET /internal/router/providers` — circuit breaker state per acquirer.
- `GET /internal/router/payments/{paymentId}/attempts` — what was tried, in order, and why each
  attempt ended.
- `GET /api/v1/ledger/accounts/{accountCode}/balance` — derived from the journal, admin-gated.
- `GET /api/v1/ledger/entries?referenceId={paymentId}` — every transaction and both sides of each.
- `GET /api/v1/settlements/{settlementId}` — a payout and the payments inside it.
- `POST /api/v1/settlements/run` — close a settlement window explicitly.

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

A settlement's totals always equal the sum of its items, and `fee + net == gross` at both levels.
Verified end to end: three payments totalling 39999 produced one payout of gross 39999, fees 800,
net 39199, reconciling exactly against the ledger's `MERCHANT_PAYABLE` balance for that merchant.

```bash
curl -X POST http://localhost:8087/api/v1/settlements/run -H "X-Admin-Token: dev-admin-token"
```

```bash
curl http://localhost:8087/api/v1/settlements/<SETTLEMENT_ID> -H "X-Admin-Token: dev-admin-token"
```

## Event Delivery

The outbox relay claims rows with `FOR UPDATE SKIP LOCKED`, so running several replicas of
payment-service divides the work instead of publishing every event once per replica. Published
rows are purged after a retention window; `payment_events` remains the durable history.

A message a consumer cannot process goes to `<topic>.dlq.v1` after a few quick retries, carrying
the exception type, message, and stack trace in its headers. Spring Kafka's default is to retry
ten times and then drop the record, which in a payment system is the worst option available: the
event is gone, nothing alerts, and a payment simply stops advancing with no trace of why.

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
platform/
  docker/
  observability/
services/
  auth-service/
  gateway-service/
  merchant-service/
  payment-service/
```

- `services/` keeps deployable applications isolated.
- `libs/` holds cross-cutting code that multiple services share.
- `platform/` stores infrastructure assets instead of mixing them with app code.
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
- CI running unit and integration tests on JDK 21 and 25

Not yet built (see [docs/roadmap.md](docs/roadmap.md)):

- refunds, users, and audit logs
- Kafka event publishing and the transactional outbox — `payment_events` is written but nothing
  relays it yet
- ledger, mock banks, provider routing, settlement, fraud, webhooks
- service Dockerfiles and Kubernetes manifests
- any real money movement: payments are recorded, never sent to a provider
