# Engineering Conventions

## Service Conventions

- one bounded context per service
- one database per service
- no direct table sharing across services
- all external APIs versioned under `/api/v1`

## Runtime Conventions

- expose health, info, and Prometheus metrics
- enable graceful shutdown
- propagate `X-Correlation-Id`
- use structured, searchable logs

## Database Conventions

- use Flyway for all schema changes
- store money as `BIGINT` in the currency's minor units, never as a float, double, or decimal
- use `TIMESTAMP WITH TIME ZONE` for every timestamp; a plain `TIMESTAMP` silently discards the
  offset written by an `OffsetDateTime`
- use UUIDs for domain identifiers
- prefer append-only history for financial records

### Money

Amounts are integers in the currency's smallest unit: `10000` is USD 100.00, and `100` is JPY 100
because the yen has no minor unit. This is how Stripe, Razorpay, and Adyen model money, and it is
what the architecture document specifies.

Integers remove rounding and scale from every conversation. There is no "which scale did you
mean", no drift, and no per-service agreement to maintain once ledger entries, refunds, and
settlements all carry amounts.

Two consequences worth knowing:

- APIs accept and return integers. A fractional amount is a client error, not something to round.
  `spring.jackson.deserialization.accept-float-as-int` is disabled in payment-service, because
  Jackson's default is to truncate `10.99` to `10` — silently charging the wrong amount.
- Presentation-layer formatting needs the currency's minor-unit exponent (2 for USD, 0 for JPY,
  3 for KWD). That belongs in whatever renders an amount, not in the storage model.

## Testing Conventions

- context smoke tests for every service
- integration tests for operational endpoints
- Testcontainers for infrastructure-backed tests in later phases

## Delivery Conventions

- each phase must keep the repo runnable
- each phase should be commit-able independently
- architecture docs change before complex implementation changes
