# ADR-0009: `SETTLED` is not a payment status

**Status:** Accepted

## Context

The architecture document lists `SETTLED` among the payment states, and it reads naturally: a
payment is created, authorised, captured, and eventually settled.

settlement-service already tracks this. A captured payment becomes a `settlement_item` with status
`PENDING`, and when a window closes it joins a batch and becomes `SETTLED`.

Adding `SETTLED` to `PaymentStatus` would mean settlement-service publishing an event that
payment-service consumes to move the payment.

## Context that decided it

A payment can be captured and then partially refunded, netted against other refunds in the same
window, or carried forward when a window nets negative. In each of those cases, "is this payment
settled" has an answer that lives in settlement-service's tables and nowhere else.

## Decision

`SETTLED` is not a payment status. `CAPTURED` is terminal for the payment, and whether the merchant
has been paid is settlement-service's question.

## Consequences

**One source of truth for whether a merchant has been paid.** With the status duplicated, the two
would eventually disagree — a settlement rolled back, a carry-forward, a payment settled in a window
that was later corrected — and there would be no rule for which one wins.

**A merchant asking "has this been paid out" makes two calls**: read the payment, then read
`/api/v1/settlements` to find the batch containing it. Less convenient than one field.

**`CAPTURED` leads only to `REFUNDED`.** The payment's lifecycle genuinely ends at capture, which
makes the state machine smaller and every transition in it about the payment rather than about
money movement elsewhere.

**The architecture document is now wrong about this**, and the `PaymentStatus` enum says so in a
comment rather than quietly diverging.

## Alternatives considered

**Add `SETTLED`, driven by `settlement.created.v1`.** Rejected as above: a second truth about
payment, with no tie-break rule.

**A derived `settled` boolean on the payment API, fetched from settlement-service on read.** Keeps
one source of truth and gives the convenient field. Rejected for now because it puts a synchronous
call to settlement-service on the read path of every payment — the same trade already made for
acquirer attempts, but this time on the hottest read on the platform rather than on one panel.
