# Architecture decision records

Decisions that were genuinely contested — where the alternative was reasonable, and the reason for
choosing against it is not obvious from the code.

Deliberately not one per feature. An ADR that records "we used PostgreSQL" says nothing anybody
needed written down; these are the ones where a future reader would otherwise look at the code and
reasonably ask *why on earth is it like this*.

| ADR | Decision | Status |
| --- | --- | --- |
| [0001](0001-transactional-outbox.md) | A transactional outbox instead of publishing after commit | Accepted |
| [0002](0002-three-credential-tiers.md) | Three operator token tiers instead of one admin token | Accepted |
| [0003](0003-fraud-gate-fails-open.md) | Risk screening fails open, and says so in the payment | Accepted |
| [0004](0004-rules-as-data.md) | Fraud and routing rules live in tables, not configuration | Accepted |
| [0005](0005-database-per-service.md) | A database per service, including a per-service audit log | Accepted |
| [0006](0006-money-as-minor-units.md) | Money is `BIGINT` minor units everywhere | Accepted |
| [0007](0007-release-held-payments-by-event.md) | A held payment is released by an event, not a callback | Accepted |
| [0008](0008-first-match-rule-evaluation.md) | Rules evaluate first-match, not most-severe | Accepted |
| [0009](0009-no-settled-payment-status.md) | `SETTLED` is not a payment status | Accepted |
| [0010](0010-correlation-id-not-tracing.md) | Correlation IDs instead of a distributed tracing backend | Accepted |

## Format

Context, Decision, Consequences. No template ceremony beyond that: the value is in the "we
considered X and rejected it because Y" paragraph, and everything else is packaging.
