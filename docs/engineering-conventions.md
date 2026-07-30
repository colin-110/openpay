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
- store money as `NUMERIC(19,4)`, never as a float or double
- reject request amounts with more precision than the column holds, rather than letting the
  database round them silently
- use `TIMESTAMP WITH TIME ZONE` for every timestamp; a plain `TIMESTAMP` silently discards the
  offset written by an `OffsetDateTime`
- use UUIDs for domain identifiers
- prefer append-only history for financial records

Money is stored as exact fixed-point decimal rather than integer minor units. Postgres `NUMERIC`
is exact, so there is no float drift, and 4 decimal places leaves room for currencies with more
than two and for fractional-unit fees. The tradeoff is that every writer must validate scale on
the way in, which `CreatePaymentRequest` does with `@Digits`.

## Testing Conventions

- context smoke tests for every service
- integration tests for operational endpoints
- Testcontainers for infrastructure-backed tests in later phases

## Delivery Conventions

- each phase must keep the repo runnable
- each phase should be commit-able independently
- architecture docs change before complex implementation changes
