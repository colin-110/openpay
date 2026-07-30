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
- store money in minor units
- use UUIDs for domain identifiers
- prefer append-only history for financial records

## Testing Conventions

- context smoke tests for every service
- integration tests for operational endpoints
- Testcontainers for infrastructure-backed tests in later phases

## Delivery Conventions

- each phase must keep the repo runnable
- each phase should be commit-able independently
- architecture docs change before complex implementation changes
