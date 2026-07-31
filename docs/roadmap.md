# OpenPay Development Roadmap

Each phase is sized for approximately 2 to 5 days and must leave the repository in a working state.

## Delivered So Far

Phases 1 to 3 are implemented, plus the asynchronous provider flow that phases 5, 6, and part of 7
describe. The system now routes a payment to an acquirer, fails over when one is unhealthy, and
completes the payment from a signature-verified callback.

Built:

- phases 1-3, except the gaps listed below
- `ledger-service` (phase 4) with a database-enforced append-only journal
- `settlement-service` (phase 8) accruing payables and batching payouts
- `mock-bank-service` (phase 5), deployed twice as mock-bank-a and mock-bank-b
- `provider-router-service` (phase 6) with priority routing, failover, and a circuit breaker
- `webhook-service` (phase 10's inbound half) with HMAC verification and deduplication
- transactional outbox and Kafka event backbone (the core of phase 7)

Still open inside those phases:

- Phase 2: `users`, `audit_logs`, and `POST /api/v1/auth/login`
- Phase 3: `POST /api/v1/refunds`
- Phase 6: routing rules are static configuration, not a `provider_routing_rules` table
- Phase 7: DLQ topics exist, but there is no replay tool; messages land there and must be
  re-published by hand
- Phase 8: payouts are batched and clear the ledger payable, but no money is actually sent
  anywhere; there is no payout rail
- Phase 10: outbound merchant webhooks are not built, only inbound provider callbacks

Money is stored as `BIGINT` minor units throughout, matching the architecture document.

## Phase 1 - Project Initialization

### Goal

Create the monorepo foundation, architecture docs, service skeleton structure, shared engineering standards, local infrastructure, and baseline operational conventions.

### New Concepts Learned

- monorepo structure for microservices
- Maven parent and module strategy
- service bootstrapping standards
- Flyway migration lifecycle
- observability-first project setup
- local platform composition with Docker Compose

### Folder Changes

- create `docs/`
- create `services/`
- create `libs/`
- create `platform/docker/`
- create `platform/observability/`
- create `.github/workflows/`
- create root `pom.xml`
- create parent and BOM conventions

### Database Changes

- no business tables yet
- optional bootstrap database creation per service
- establish Flyway baseline folder structure in each stateful service

### REST Endpoints

- `GET /actuator/health`
- `GET /actuator/info`
- `GET /actuator/prometheus`
- optional `GET /api/v1/ping` in gateway only

### Kafka Topics

- none required yet

### Tests

- smoke test for Spring context per service
- Testcontainers-based PostgreSQL connectivity test for one sample service
- health endpoint integration test

### Documentation

- software architecture document
- roadmap
- repo README
- local setup guide
- engineering conventions

### Expected Git Commit Message

`chore: initialize openpay monorepo and platform foundation`

## Phase 2 - Authentication and Merchant Identity

### Goal

Implement merchant onboarding primitives, API key issuance/validation, user auth basics, and gateway-auth integration.

### New Concepts Learned

- API key hashing
- auth delegation
- role-based access
- audit logging

### Folder Changes

- add `auth-service`
- add `merchant-service`
- add shared security library

### Database Changes

- `merchants`
- `users`
- `api_keys`
- `audit_logs`

### REST Endpoints

- `POST /api/v1/merchants`
- `GET /api/v1/merchants/{merchantId}`
- `POST /api/v1/api-keys`
- `POST /api/v1/auth/login`
- `POST /api/v1/auth/validate-key`

### Kafka Topics

- `merchant.created.v1`
- `apikey.created.v1`

### Tests

- API key validation tests
- duplicate key prevention tests
- auth integration tests
- migration tests

### Documentation

- auth model
- merchant onboarding flow
- API key lifecycle

### Expected Git Commit Message

`feat: add merchant identity and api key authentication`

## Phase 3 - Payment API and State Machine

### Goal

Implement payment creation API, idempotency, optimistic locking, and the core payment state machine.

### New Concepts Learned

- idempotent writes
- request fingerprinting
- state machine enforcement
- optimistic concurrency control

### Folder Changes

- add `payment-service`
- add shared idempotency utilities

### Database Changes

- `payments`
- `payment_events`
- `idempotency_records` if separated from payments

### REST Endpoints

- `POST /api/v1/payments`
- `GET /api/v1/payments/{paymentId}`
- `GET /api/v1/payments`
- `POST /api/v1/refunds`

### Kafka Topics

- `payment.created.v1`
- `payment.status-updated.v1`

### Tests

- idempotency replay tests
- invalid transition tests
- pagination tests
- concurrent update tests

### Documentation

- payment API spec
- payment state diagram
- error model

### Expected Git Commit Message

`feat: implement payment api with idempotency and state machine`

## Phase 4 - Ledger Service

### Goal

Build the accounting core with double-entry postings for successful payments and refunds.

### New Concepts Learned

- double-entry bookkeeping
- append-only journal design
- accounting invariants

### Folder Changes

- add `ledger-service`
- add accounting domain library if needed

### Database Changes

- `ledger_entries`
- `ledger_accounts`
- `ledger_transactions`

### REST Endpoints

- `GET /api/v1/ledger/entries`
- `GET /api/v1/ledger/accounts/{accountCode}/balance`

### Kafka Topics

- `ledger.posting-requested.v1`
- `ledger.entry-posted.v1`

### Tests

- balanced transaction tests
- duplicate posting idempotency tests
- projection/balance tests

### Documentation

- chart of accounts
- posting rules
- ledger consistency notes

### Expected Git Commit Message

`feat: add double-entry ledger service`

## Phase 5 - Mock Banks

### Goal

Create simulated external providers with realistic delays, webhook callbacks, and failure patterns.

### New Concepts Learned

- provider simulation
- callback-driven completion
- failure injection

### Folder Changes

- add `mock-bank-a`
- add `mock-bank-b`

### Database Changes

- provider-local tracking optional only

### REST Endpoints

- `POST /provider/payments`
- `GET /provider/transactions/{id}`

### Kafka Topics

- none required inside mock providers

### Tests

- callback contract tests
- timeout simulation tests
- signature generation tests

### Documentation

- provider behavior matrix
- mock failure scenarios

### Expected Git Commit Message

`feat: add mock bank providers with webhook simulation`

## Phase 6 - Provider Router

### Goal

Introduce routing strategies, provider failover, retries, and circuit breakers.

### New Concepts Learned

- strategy pattern for routing
- resilience4j breakers and retries
- provider health-based decisioning

### Folder Changes

- add `provider-router-service`

### Database Changes

- `provider_transactions`
- `provider_routing_rules`

### REST Endpoints

- internal admin endpoints for routing config

### Kafka Topics

- `payment.routing-requested.v1`
- `payment.provider-dispatched.v1`

### Tests

- primary provider selection tests
- failover tests
- circuit breaker state tests

### Documentation

- routing strategy design
- failover behavior

### Expected Git Commit Message

`feat: add provider router with failover and circuit breakers`

## Phase 7 - Kafka and Outbox Integration

### Goal

Formalize event-driven service integration with transactional outbox, Kafka consumers, retry policies, and DLQs.

### New Concepts Learned

- outbox relays
- at-least-once semantics
- consumer idempotency
- dead-letter handling

### Folder Changes

- add shared Kafka library
- add outbox relay components

### Database Changes

- `outbox_events` in producer services
- `consumer_offsets` or inbox tables where appropriate

### REST Endpoints

- optional operational endpoints for replay/admin

### Kafka Topics

- all core payment workflow topics
- DLQ topics

### Tests

- outbox publication tests
- duplicate consumption tests
- DLQ routing tests

### Documentation

- event catalog
- topic naming conventions
- replay procedures

### Expected Git Commit Message

`feat: add kafka event backbone with outbox and dlq support`

## Phase 8 - Settlement Service

### Goal

Implement merchant settlement aggregation and payout batch lifecycle.

### New Concepts Learned

- settlement windows
- payout batching
- payable reconciliation

### Folder Changes

- add `settlement-service`

### Database Changes

- `settlements`
- `settlement_items`

### REST Endpoints

- `GET /api/v1/settlements`
- `GET /api/v1/settlements/{settlementId}`

### Kafka Topics

- `settlement.created.v1`
- `settlement.completed.v1`

### Tests

- eligibility tests
- batch aggregation tests
- duplicate settlement prevention tests

### Documentation

- settlement lifecycle
- settlement formula assumptions

### Expected Git Commit Message

`feat: implement merchant settlement processing`

## Phase 9 - Fraud Detection

### Goal

Add rule-based fraud checks before and after provider submission.

### New Concepts Learned

- synchronous risk gating
- async review events
- configurable rules

### Folder Changes

- add `fraud-service`

### Database Changes

- `fraud_rules`
- `fraud_decisions`

### REST Endpoints

- internal rules management endpoints

### Kafka Topics

- `fraud.check-requested.v1`
- `fraud.check-completed.v1`

### Tests

- rules evaluation tests
- allow/block/review flow tests

### Documentation

- fraud rules model
- decision integration points

### Expected Git Commit Message

`feat: add fraud detection service`

## Phase 10 - Notification and Webhooks

### Goal

Implement outbound merchant notifications and inbound provider webhook processing with verification and retries.

### New Concepts Learned

- webhook trust boundaries
- signature verification
- outbound delivery retries

### Folder Changes

- add `webhook-service`
- add `notification-service`

### Database Changes

- `provider_webhook_events`
- `merchant_webhook_deliveries`

### REST Endpoints

- `POST /internal/provider/webhooks/{provider}`
- `POST /api/v1/webhooks/test`
- `GET /api/v1/webhooks/deliveries`

### Kafka Topics

- `provider.callback-received.v1`
- `notification.requested.v1`

### Tests

- duplicate webhook tests
- signature verification tests
- retry and DLQ tests

### Documentation

- webhook signature scheme
- delivery retry policy

### Expected Git Commit Message

`feat: add provider webhooks and merchant notification delivery`

## Phase 11 - Observability

### Goal

Add production-style metrics, tracing, dashboards, log aggregation, and alert-ready telemetry conventions.

### New Concepts Learned

- OpenTelemetry propagation
- RED metrics
- service dashboards
- correlation-driven debugging

### Folder Changes

- add `platform/observability/prometheus`
- add `platform/observability/grafana`
- add `platform/observability/loki`

### Database Changes

- none

### REST Endpoints

- actuator and metrics endpoints hardened and standardized

### Kafka Topics

- none

### Tests

- metrics exposure tests
- trace propagation integration tests

### Documentation

- dashboard inventory
- alert ideas
- observability conventions

### Expected Git Commit Message

`feat: add observability stack with metrics tracing and logs`

## Phase 12 - Dockerized Local Platform

### Goal

Make the system runnable end-to-end with Docker Compose.

### New Concepts Learned

- service containerization
- dependency boot ordering
- local environment parity

### Folder Changes

- add service Dockerfiles
- complete compose stack

### Database Changes

- none

### REST Endpoints

- no new business endpoints

### Kafka Topics

- all previously defined topics provisioned

### Tests

- compose smoke tests
- end-to-end local happy-path test

### Documentation

- local runbook
- troubleshooting guide

### Expected Git Commit Message

`chore: dockerize openpay local platform`

## Phase 13 - Kubernetes Deployment

### Goal

Add deployable Kubernetes manifests or Helm charts for services and platform dependencies.

### New Concepts Learned

- k8s probes
- configmaps and secrets
- horizontal scaling

### Folder Changes

- add `platform/k8s/`

### Database Changes

- none

### REST Endpoints

- no new business endpoints

### Kafka Topics

- none

### Tests

- manifest validation
- probe readiness checks

### Documentation

- deployment guide
- scaling assumptions

### Expected Git Commit Message

`chore: add kubernetes deployment manifests`

## Phase 14 - Load Testing and Resilience Validation

### Goal

Measure throughput, latency, and failure behavior using k6 and fault scenarios.

### New Concepts Learned

- performance baselining
- bottleneck analysis
- resilience validation

### Folder Changes

- add `tests/performance/`

### Database Changes

- none

### REST Endpoints

- none

### Kafka Topics

- none

### Tests

- payment create load tests
- webhook spike tests
- provider outage scenarios

### Documentation

- baseline performance report
- known bottlenecks

### Expected Git Commit Message

`test: add load and resilience test suite`

## Phase 15 - CI/CD

### Goal

Automate build, test, image packaging, and quality gates with GitHub Actions.

### New Concepts Learned

- pipeline stages
- artifact caching
- branch protections

### Folder Changes

- expand `.github/workflows/`

### Database Changes

- none

### REST Endpoints

- none

### Kafka Topics

- none

### Tests

- CI workflow validation
- container build verification

### Documentation

- pipeline overview
- merge quality gates

### Expected Git Commit Message

`ci: add github actions build and quality pipelines`

## Phase 16 - Architecture Diagrams and Final Documentation

### Goal

Produce polished diagrams, ADRs, and full project documentation suitable for portfolio review.

### New Concepts Learned

- architecture communication
- decision records
- system storytelling

### Folder Changes

- add `docs/diagrams/`
- add `docs/adrs/`

### Database Changes

- none

### REST Endpoints

- none

### Kafka Topics

- none

### Tests

- documentation review checklist

### Documentation

- system context diagram
- container diagram
- sequence diagrams
- ER diagram
- state diagram
- deployment diagram
- Kafka topic flow diagram

### Expected Git Commit Message

`docs: add architecture diagrams and decision records`

## Phase 17 - Final Production-Grade Release

### Goal

Harden the system as a polished portfolio release with security review, runbooks, demos, and final cleanup.

### New Concepts Learned

- release hardening
- operational readiness
- portfolio presentation

### Folder Changes

- finalize all service docs
- add release notes

### Database Changes

- final schema tuning and indexes

### REST Endpoints

- no new major endpoints unless gap fixes are needed

### Kafka Topics

- no new major topics unless gap fixes are needed

### Tests

- end-to-end regression suite
- smoke tests
- resiliency regression

### Documentation

- release checklist
- demo script
- operations runbook
- architecture summary

### Expected Git Commit Message

`release: prepare openpay production-grade portfolio release`

## Recommended Phase 1 Output Checklist

Phase 1 is complete only if the repository contains:

- architecture and roadmap docs
- monorepo directory skeleton
- root Maven parent
- at least one runnable Spring Boot service skeleton
- Flyway baseline wiring
- actuator and Prometheus metrics exposure
- Docker Compose with PostgreSQL, Redis, Kafka, Prometheus, Grafana, Loki placeholders or initial wiring
- README with local boot instructions

## Recommended Diagrams To Produce

- system context diagram
- service communication diagram
- payment sequence diagram
- provider callback sequence diagram
- payment state machine diagram
- ER diagram
- deployment diagram
- Kafka topic flow diagram
