# ADR-0001: A transactional outbox instead of publishing after commit

**Status:** Accepted

## Context

Creating a payment has to do two things: write a row, and tell the rest of the platform about it.
Routing, ledger postings, settlement accrual, and the merchant's webhook are all driven by
`payment.created.v1` and `payment.status-updated.v1`.

A database and a message broker cannot be committed together. Whichever order they are done in,
there is a window:

- **Publish after the commit.** The process dies between the two. The payment exists and nothing
  knows about it: it is never routed, never captured, never settled, and sits in `CREATED` forever
  with no error anywhere. A merchant took an order and the platform silently did nothing with it.
- **Publish before the commit.** The transaction rolls back after the publish succeeds. Now the
  event exists for a payment that does not, and every consumer acts on a payment id that will never
  resolve — including the ledger, which would post entries against nothing.

Both windows are small. Both are money.

## Decision

Write the event to an `outbox_events` table **in the same transaction as the business row**. A
separate relay polls unpublished rows and sends them to Kafka.

The event cannot escape without the payment, and the payment cannot commit without the event,
because they are the same commit.

The relay claims rows with `SELECT ... FOR UPDATE SKIP LOCKED`, which is what makes running more
than one replica divide the work rather than publish everything twice. Without it, scaling
payment-service out would silently multiply every event on the platform.

## Consequences

**Delivery is at-least-once, not exactly-once.** The relay can publish a row and die before marking
it published, and will then publish it again. That is the right way round — a duplicated event is
recoverable and a lost one is not — but it makes idempotent consumers mandatory rather than
optional. Every consumer here is built for it: the router checks for an existing provider
transaction, the ledger and settlement enforce uniqueness on the payment id, and payment-service
treats a transition to the state it is already in as a duplicate.

**There is a delay.** The relay polls every 500ms, so an event is published up to half a second
after the payment is committed. Nothing on the merchant's path waits for it.

**The backlog is now a thing that can grow.** A stalled relay does not fail anything: payments are
accepted, committed, and then quietly stop advancing. That failure has no error to alert on, which
is why `openpay_outbox_unpublished` is exported as a gauge and is the first panel on the Payment
Flow dashboard.

**Published rows have to be cleaned up.** The outbox is a delivery mechanism, not an audit log —
`payment_events` is the durable history — so published rows are purged after a retention window,
because they sit on the hot path of the relay's index scan.

## Alternatives considered

**Kafka transactions.** Genuinely solves the problem for Kafka-to-Kafka flows, but not for
database-to-Kafka: the database write is still outside the transaction. It would have added a
second consistency mechanism without removing the first.

**Change data capture (Debezium).** Reads the write-ahead log, so nothing in the application has to
know about the outbox at all — a better answer at scale. Rejected here because it means running and
operating a connector cluster alongside everything else, and the outbox table is a hundred lines
that every developer can read.
