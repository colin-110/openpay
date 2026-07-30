# OpenPay Software Architecture Document

## 1. Purpose

OpenPay is a production-grade distributed payment gateway designed as a portfolio system that demonstrates realistic backend engineering patterns used in companies such as Stripe, Adyen, Razorpay, and enterprise payment orchestration platforms.

The system is intentionally designed around:

- high-throughput payment processing
- strong consistency where money is involved
- eventual consistency across service boundaries
- reliability under partial failures
- auditability and traceability
- event-driven workflows
- operational observability

This document defines the target architecture before implementation.

## 2. Product Scope

OpenPay supports:

- merchant onboarding and API key management
- authenticated payment initiation
- idempotent payment requests
- provider routing and failover
- asynchronous bank/provider callbacks
- payment lifecycle tracking
- double-entry ledger posting
- settlement batch generation
- merchant notifications and webhooks
- fraud checks
- operational analytics

Out of scope for the first release:

- real card network integrations
- PCI DSS card vaulting
- chargebacks and disputes
- multi-region active-active deployment
- complex fee engines

## 3. Non-Functional Requirements

### Availability

- target availability: 99.9% for local development architecture, extensible toward 99.95%
- graceful degradation under dependency failures
- no single point of failure at service level

### Performance

- payment API p95 latency target: under 300 ms for synchronous acceptance path
- asynchronous provider completion path supported through events/webhooks
- support bursty workloads through Kafka buffering

### Reliability

- at-least-once event delivery with idempotent consumers
- no double-charging from client retries
- no lost financial postings

### Security

- signed API authentication
- least-privilege service-to-service auth
- encrypted secrets
- immutable audit trails

### Operability

- structured logs
- distributed tracing
- RED/USE metrics
- liveness/readiness/startup probes

## 4. Architectural Principles

- money movement is modeled explicitly, not hidden in CRUD handlers
- synchronous APIs are used only for edge acceptance and control-plane operations
- business progression happens through durable events
- every externally retryable write must be idempotent
- ledger is append-only
- each service owns its data store schema
- cross-service consistency uses saga orchestration/choreography, not distributed transactions
- internal APIs prefer gRPC where low-latency service contracts matter

## 5. High-Level Architecture

### Edge and Core Services

1. Gateway Service
   - external REST entry point
   - rate limiting
   - correlation ID propagation
   - auth delegation
   - request normalization

2. Authentication Service
   - merchant user auth
   - API key validation
   - key rotation and scope checks

3. Merchant Service
   - merchant profile
   - onboarding state
   - webhook endpoints
   - provider configuration

4. Payment Service
   - payment creation
   - payment state machine
   - idempotency handling
   - refund initiation
   - outbox emission of payment events

5. Provider Router Service
   - routing strategy selection
   - provider failover
   - circuit breaker and retry policy
   - provider transaction tracking

6. Mock Bank A / Mock Bank B
   - simulated external acquirers/banks
   - variable latency, failures, timeout behavior
   - webhook callback emission

7. Webhook Service
   - inbound provider callbacks
   - signature verification
   - deduplication
   - transformation into internal domain events

8. Ledger Service
   - double-entry posting
   - immutable journal
   - account balance projection
   - accounting invariants

9. Settlement Service
   - settlement eligibility
   - merchant payable calculation
   - batch lifecycle

10. Fraud Detection Service
   - rule-based checks in early versions
   - synchronous pre-check and async post-factum review

11. Notification Service
   - email/webhook dispatch
   - retry and dead-letter handling

12. Analytics Service
   - aggregated views
   - operational and business reporting

13. Configuration Service
   - provider priorities
   - feature flags
   - fraud rules
   - rate limit policies

## 6. Service Boundaries

### Domain Ownership

- Gateway owns ingress concerns only
- Authentication owns credentials and identity validation
- Merchant owns merchant business configuration
- Payment owns payment intent and lifecycle
- Provider Router owns provider selection decisions
- Webhook owns callback ingestion trust boundary
- Ledger owns financial truth
- Settlement owns payout and settlement workflows
- Fraud owns risk decisioning
- Notification owns outbound merchant communication
- Analytics owns denormalized read models

### Why This Split

- separates money state from transport concerns
- keeps the ledger isolated from provider instability
- enables independent scaling for hot paths
- reflects common payment platform decomposition

## 7. Data Architecture

PostgreSQL is the source of truth per service. Redis is used only for ephemeral speed paths. Kafka is the durable event backbone.

### Data Rules

- one database schema per service
- no shared tables across services
- cross-service reads via APIs or materialized projections
- transactional outbox per service for event publication
- financial records are append-only wherever practical

## 8. Canonical Payment Flow

1. Merchant sends `POST /api/v1/payments` with idempotency key.
2. Gateway applies rate limits, correlation ID, and auth checks.
3. Payment Service validates request, persists payment in `RECEIVED` or `PENDING_PROVIDER`, and writes outbox event.
4. Kafka publishes `payment.created`.
5. Provider Router consumes event, chooses provider using strategy rules, creates provider transaction, calls Mock Bank.
6. Mock Bank accepts request and later emits provider webhook.
7. Webhook Service verifies signature, deduplicates, persists callback, publishes `provider.callback.received`.
8. Payment Service consumes callback event, advances payment state.
9. Ledger Service consumes terminal payment success/failure events and posts journal entries.
10. Settlement Service consumes ledger/payment events and creates settlement batches.
11. Notification Service sends merchant notifications/webhooks.
12. Analytics Service updates read models.

## 9. Payment State Machine

Primary states:

- `CREATED`
- `VALIDATING`
- `REQUIRES_ROUTING`
- `ROUTING`
- `PENDING_PROVIDER`
- `AUTHORIZED`
- `CAPTURED`
- `FAILED`
- `CANCELLED`
- `REFUND_PENDING`
- `REFUNDED`
- `SETTLED`

Allowed transitions:

- `CREATED -> VALIDATING`
- `VALIDATING -> REQUIRES_ROUTING`
- `REQUIRES_ROUTING -> ROUTING`
- `ROUTING -> PENDING_PROVIDER`
- `PENDING_PROVIDER -> AUTHORIZED`
- `PENDING_PROVIDER -> CAPTURED`
- `PENDING_PROVIDER -> FAILED`
- `AUTHORIZED -> CAPTURED`
- `AUTHORIZED -> CANCELLED`
- `CAPTURED -> REFUND_PENDING`
- `REFUND_PENDING -> REFUNDED`
- `CAPTURED -> SETTLED`

State transition rules:

- transitions must be monotonic and validated centrally
- terminal states reject duplicate reprocessing except idempotent replay
- every transition produces a payment event row and an outbox message
- optimistic locking prevents concurrent invalid transitions

## 10. Reliability Patterns

### Idempotency

- external clients send `Idempotency-Key`
- key scope: merchant + endpoint + request hash
- store original response payload and status
- mismatched replay body returns conflict

### Outbox Pattern

- domain write and outbox insert happen in one local DB transaction
- async publisher relays outbox to Kafka
- consumer idempotency ensures safe replay

### Saga Pattern

- payment lifecycle spans Payment, Provider Router, Webhook, Ledger, Settlement
- orchestration is mostly event-driven choreography
- compensations include marking failure, refund, or reversal where applicable

### Retry and DLQ

- transient failures use bounded exponential backoff
- poison messages are routed to Kafka DLQ topics
- operators can replay after inspection

### Circuit Breaker

- provider call clients wrap with Resilience4j
- per-provider breaker and bulkhead configuration
- degraded mode triggers alternate provider routing

### Provider Failover

- routing strategy evaluates priority, capability, health, and recent failure rate
- failover only occurs before terminal provider acceptance unless business rules allow retries

## 11. Security Model

### External Security

- merchant API keys with prefix + hashed secret storage
- HMAC-based webhook signature verification
- optional JWT for dashboard/admin access
- per-merchant scopes and status flags

### Internal Security

- service-to-service auth via mTLS or signed internal tokens in later phases
- secrets via environment variables or secret manager abstraction

### Auditability

- immutable audit log for auth events, config changes, refunds, settlements, and manual operations

## 12. API Design

### External API

- REST only
- versioned as `/api/v1/...`
- idempotency required on payment/refund creation
- cursor-based pagination for large lists
- consistent error envelope

### Internal API

- gRPC where request-response between services is latency-sensitive
- REST for lower-frequency admin/config integrations

### Standard Headers

- `X-Correlation-Id`
- `Idempotency-Key`
- `X-Request-Timestamp`
- `X-Api-Version`

## 13. Database Schema Design

### payments

Purpose: source of truth for payment lifecycle.

Important columns:

- `id` UUID PK
- `merchant_id` UUID FK reference by owned ID
- `external_reference` VARCHAR(100)
- `idempotency_key` VARCHAR(100)
- `amount` BIGINT
- `currency` CHAR(3)
- `status` VARCHAR(50)
- `provider_selected` VARCHAR(50)
- `request_fingerprint` VARCHAR(64)
- `version` BIGINT
- `created_at`, `updated_at`

Indexes:

- unique `(merchant_id, idempotency_key)`
- index `(merchant_id, created_at desc)`
- index `(status, created_at)`
- index `(external_reference)`

Design notes:

- amount stored in minor units
- optimistic lock version guards transitions

### payment_events

Purpose: append-only timeline of state changes and important actions.

Columns:

- `id` UUID PK
- `payment_id` UUID FK
- `event_type` VARCHAR(100)
- `from_status` VARCHAR(50)
- `to_status` VARCHAR(50)
- `payload` JSONB
- `correlation_id` VARCHAR(100)
- `occurred_at`

Indexes:

- index `(payment_id, occurred_at)`
- index `(event_type, occurred_at)`

### ledger_entries

Purpose: immutable journal entries.

Columns:

- `id` UUID PK
- `transaction_id` UUID
- `payment_id` UUID
- `entry_type` VARCHAR(20)`DEBIT|CREDIT`
- `account_code` VARCHAR(50)
- `amount` BIGINT
- `currency` CHAR(3)
- `reference_type` VARCHAR(50)
- `reference_id` UUID
- `created_at`

Indexes:

- index `(transaction_id)`
- index `(payment_id)`
- index `(account_code, created_at)`

Design notes:

- enforce equal debit and credit totals per transaction in service logic and DB validations where feasible

### merchants

Columns:

- `id` UUID PK
- `merchant_code` VARCHAR(50) unique
- `legal_name` VARCHAR(255)
- `status` VARCHAR(30)
- `webhook_url` TEXT
- `default_currency` CHAR(3)
- `created_at`, `updated_at`

Indexes:

- unique `(merchant_code)`
- index `(status)`

### users

Columns:

- `id` UUID PK
- `merchant_id` UUID FK
- `email` VARCHAR(255) unique
- `password_hash`
- `role`
- `status`
- `created_at`

Indexes:

- unique `(email)`
- index `(merchant_id)`

### api_keys

Columns:

- `id` UUID PK
- `merchant_id` UUID FK
- `key_prefix` VARCHAR(20)
- `key_hash` VARCHAR(255)
- `scope` VARCHAR(100)
- `status` VARCHAR(20)
- `last_used_at`
- `expires_at`
- `created_at`

Indexes:

- unique `(key_prefix)`
- index `(merchant_id, status)`

### webhooks

Purpose: both merchant outbound registrations and inbound provider callback tracking can be modeled separately; for clarity, keep two tables in implementation: `merchant_webhooks` and `provider_webhook_events`. This roadmap keeps `webhooks` as the conceptual domain entity.

Representative columns:

- `id` UUID PK
- `source_type` VARCHAR(30)
- `event_type` VARCHAR(100)
- `signature_verified` BOOLEAN
- `delivery_status` VARCHAR(30)
- `payload` JSONB
- `received_at`

Indexes:

- index `(source_type, received_at)`
- index `(delivery_status)`

### settlements

Columns:

- `id` UUID PK
- `merchant_id` UUID FK
- `settlement_date` DATE
- `currency` CHAR(3)
- `gross_amount` BIGINT
- `fee_amount` BIGINT
- `net_amount` BIGINT
- `status` VARCHAR(30)
- `created_at`, `updated_at`

Indexes:

- index `(merchant_id, settlement_date desc)`
- index `(status, settlement_date)`

### refunds

Columns:

- `id` UUID PK
- `payment_id` UUID FK
- `merchant_id` UUID FK
- `amount` BIGINT
- `currency` CHAR(3)
- `status` VARCHAR(30)
- `reason` VARCHAR(255)
- `idempotency_key` VARCHAR(100)
- `created_at`, `updated_at`

Indexes:

- unique `(merchant_id, idempotency_key)`
- index `(payment_id)`
- index `(status, created_at)`

### provider_transactions

Columns:

- `id` UUID PK
- `payment_id` UUID FK
- `provider_name` VARCHAR(50)
- `provider_reference` VARCHAR(100)
- `attempt_no` INT
- `status` VARCHAR(30)
- `request_payload` JSONB
- `response_payload` JSONB
- `failure_reason` VARCHAR(255)
- `created_at`, `updated_at`

Indexes:

- unique `(provider_name, provider_reference)`
- index `(payment_id, attempt_no)`
- index `(status, created_at)`

## 14. Kafka Topic Design

Core topics:

- `payment.created.v1`
- `payment.validated.v1`
- `payment.routing-requested.v1`
- `payment.provider-dispatched.v1`
- `provider.callback-received.v1`
- `payment.status-updated.v1`
- `ledger.posting-requested.v1`
- `ledger.entry-posted.v1`
- `settlement.created.v1`
- `notification.requested.v1`
- `fraud.check-requested.v1`
- `fraud.check-completed.v1`

DLQ topics:

- `payment.created.dlq.v1`
- `provider.callback-received.dlq.v1`
- `notification.requested.dlq.v1`

Topic conventions:

- version suffix for schema evolution
- partition keys chosen by `payment_id` or `merchant_id` depending on ordering requirement
- Avro/JSON schema evolution can be introduced later; early phase can use JSON with strict contracts

## 15. Observability Design

### Metrics

- request count, error count, latency
- payment acceptance rate
- provider success rate by provider
- webhook verification failures
- ledger posting lag
- Kafka consumer lag
- settlement processing lag

### Logging

- structured JSON logs
- correlation ID in every log line
- payment ID and merchant ID where available
- security-sensitive fields redacted

### Tracing

- OpenTelemetry instrumentation across REST, gRPC, Kafka, DB, Redis
- spans stitched by correlation ID and trace context propagation

## 16. Deployment View

### Local

- Docker Compose for app services + PostgreSQL + Redis + Kafka + Prometheus + Grafana + Loki

### Production-style

- Kubernetes deployments per service
- HPA for stateless services
- StatefulSets or managed services for Kafka/Postgres/Redis outside cluster in real production
- ingress controller at edge
- configmaps and secrets separated

## 17. Failure Scenarios

### Provider Timeout

- router retries within bounded policy
- breaker trips on repeated failure
- alternate provider selected if allowed
- payment remains non-terminal until callback timeout policy expires

### Duplicate Webhook

- webhook event dedup table keyed by provider event ID or payload fingerprint
- downstream consumers remain idempotent

### Kafka Outage

- local transaction still commits business state + outbox
- publisher resumes when Kafka recovers

### Ledger Failure

- payment completion event remains durable
- ledger retry continues asynchronously
- settlement blocked until ledger posting completes

## 18. Repository Structure

Recommended monorepo layout:

```text
openpay/
  docs/
    software-architecture-document.md
    roadmap.md
    diagrams/
  platform/
    docker/
    k8s/
    observability/
  services/
    gateway-service/
    auth-service/
    merchant-service/
    payment-service/
    provider-router-service/
    webhook-service/
    ledger-service/
    settlement-service/
    fraud-service/
    notification-service/
    analytics-service/
    config-service/
    mock-bank-a/
    mock-bank-b/
  libs/
    common-web/
    common-kafka/
    common-observability/
    common-security/
    common-test/
  scripts/
  .github/
    workflows/
```

## 19. Phase 1 Architecture Decision

Phase 1 should not start with all services. It should establish the platform skeleton that makes later phases safe and coherent.

Phase 1 service set:

- gateway-service
- auth-service
- merchant-service
- payment-service

Phase 1 platform set:

- shared Maven parent
- base Spring Boot conventions
- PostgreSQL and Flyway
- Redis
- observability starter conventions
- Docker Compose foundation

This keeps the system runnable while deferring distributed workflow complexity to later phases.

## 20. Architecture Review Checklist

The implementation should be considered aligned only if it preserves:

- idempotent payment write path
- explicit state machine transitions
- outbox-based event publication
- append-only ledger design
- isolated service ownership
- observability from day one
- operational failure handling, not just happy-path code
