# OpenPay

OpenPay is a payment gateway portfolio project built to demonstrate realistic backend
architecture, distributed systems patterns, and production engineering practices.

## Services

| Service | Port | Owns | Responsibility |
| --- | --- | --- | --- |
| `gateway-service` | 8080 | — | Front door. Authenticates merchant API keys and routes to the service that owns each path. |
| `auth-service` | 8081 | `openpay_auth` | Issues and validates API keys. Stores only key hashes. |
| `merchant-service` | 8082 | `openpay_merchant` | Merchant onboarding and lookup. |
| `payment-service` | 8083 | `openpay_payment` | Payment creation, idempotency, and the payment state machine. |

Shared code lives in `libs/`: `common-observability` (correlation IDs) and `common-security`
(API key and admin token authentication, applied per path by configuration).

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

### 4. Run the services

Each service needs its own terminal. `-am` builds the shared libraries it depends on:

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

Start order matters for a full flow: auth-service verifies merchants against merchant-service, and
payment-service verifies API keys against auth-service.

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
curl -X POST http://localhost:8080/api/v1/payments -H "X-Api-Key: <API_KEY>" -H "Idempotency-Key: order-1001" -H "Content-Type: application/json" -d '{"amount":100.00,"currency":"USD"}'
```

Advance it through the state machine:

```bash
curl -X POST http://localhost:8080/api/v1/payments/<PAYMENT_ID>/status -H "X-Api-Key: <API_KEY>" -H "Content-Type: application/json" -d '{"status":"AUTHORIZED"}'
```

## Endpoints

Merchant-facing, via the gateway on 8080, authenticated with `X-Api-Key`:

- `POST /api/v1/payments` — create a payment. Requires `Idempotency-Key`.
- `GET /api/v1/payments/{paymentId}`
- `GET /api/v1/payments?page=0&size=20`
- `POST /api/v1/payments/{paymentId}/status`

Platform-operator, authenticated with `X-Admin-Token`:

- `POST /api/v1/merchants`
- `GET /api/v1/merchants/{merchantId}`
- `GET /api/v1/merchants?page=0&size=20`
- `POST /api/v1/api-keys`

Internal:

- `POST /api/v1/auth/validate-key` — called by the gateway and payment-service.

Every service exposes `/actuator/health`, `/actuator/info`, and `/actuator/prometheus`.

## Idempotency

`POST /api/v1/payments` requires an `Idempotency-Key`. The first request creates the payment and
returns `201`. Replaying the same key with the same body returns `200` and the original payment —
no second charge. Replaying the same key with a *different* body returns `409`, because that is a
client bug rather than a retry, and silently returning the original payment would hide it.

Concurrency is handled by a unique constraint on `(merchant_id, idempotency_key)`: if two requests
race, the loser catches the constraint violation and returns the winner's payment.

## Payment State Machine

```text
CREATED ──> AUTHORIZED ──> CAPTURED
   │             │
   └─────────────┴────────> FAILED
```

`CAPTURED` and `FAILED` are terminal. Illegal transitions are refused with `409`; the rule lives on
the entity, so no caller can bypass it. Concurrent updates to the same payment are caught by an
optimistic-locking `@Version` column and surface as `409`.

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
- CI running unit and integration tests on JDK 21 and 25

Not yet built (see [docs/roadmap.md](docs/roadmap.md)):

- refunds, users, and audit logs
- Kafka event publishing and the transactional outbox — `payment_events` is written but nothing
  relays it yet
- ledger, mock banks, provider routing, settlement, fraud, webhooks
- service Dockerfiles and Kubernetes manifests
- any real money movement: payments are recorded, never sent to a provider
