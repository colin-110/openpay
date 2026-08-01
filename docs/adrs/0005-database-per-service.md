# ADR-0005: A database per service, including a per-service audit log

**Status:** Accepted

## Context

Nine services hold state. The obvious alternative to nine databases is one database with nine
schemas, which is cheaper to run and lets a join answer a question that otherwise takes two network
calls.

The question came up twice more, specifically:

- The **audit log** is the same shape in auth-service and merchant-service. A single audit database
  is a common design, and there is a real argument for it: an investigator wants one place to look.
- The **outbox** is the same table in three services, and its implementation is genuinely shared
  code.

## Decision

One database per service. No service reads another's, and there is not a single foreign key that
crosses a service boundary.

Where code is genuinely shared — the outbox in `libs/common-outbox`, the audit trail in
`libs/common-audit` — the **code** is shared and the **table** is not. Each service creates its own
in its own migrations and brings the shared entity into its own persistence context.

## Consequences

**Cross-service questions take a network call.** payment-service asks provider-router-service for a
payment's acquirer attempts rather than joining to them. The alternative was payment-service keeping
its own copy by consuming routing events, which decouples the read at the cost of a second table
saying the same thing and a window where the two disagree. The synchronous read is the honest trade:
one owner, and the only cost is that attempt history is unavailable while the router is down — which
the API reports as `503` rather than pretending the payment had no attempts.

**There is no platform-wide join.** Reconciling a payment against its ledger entries, its settlement
item, and its fraud decision means four queries against four databases. That is a real cost and it
falls on operators.

**Two audit tables instead of one.** An investigator looks in two places. Accepted because a central
audit table would make every service's writes depend on a schema none of them owns, and would be the
single outage that stops the *whole platform* recording anything — including the security events
most worth having during an incident.

**Nine sets of migrations, nine Flyway histories.** Which is also the benefit: a migration to
payment-service cannot lock a table settlement-service is reading.

## Alternatives considered

**One database, schema per service.** Cheaper, and the isolation is enforced by convention plus
permissions rather than by the network. Rejected because the boundary that is not enforced by
something mechanical is the boundary that erodes — the first cross-schema join gets written under
deadline pressure and is never removed.

**A shared audit database.** Rejected as above. The failure mode is asymmetric: a service that
cannot write its own audit row is degraded, and a platform that cannot write any audit row during an
incident is blind.
