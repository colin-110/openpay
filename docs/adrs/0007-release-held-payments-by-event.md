# ADR-0007: A held payment is released by an event, not a callback

**Status:** Accepted

## Context

When risk screening returns `REVIEW`, the payment is stored with `fraud_status = HELD` and **no
outbox row**. Routing is driven entirely by `payment.created.v1`, so withholding that event is what
stops the payment reaching an acquirer — there is no second mechanism that has to agree.

Eventually an operator closes the review. Something then has to tell payment-service, so it can
publish the event it withheld or fail the payment.

The obvious implementation is an HTTP call from fraud-service to payment-service when the operator
clicks the button.

## Decision

fraud-service publishes `fraud.check-completed.v1`. payment-service consumes it and releases or
fails the payment.

The topic carries **every** decision, including the ones payment creation already acted on
synchronously — not only the resolved reviews. payment-service ignores an event for a payment that
was never held, which is most of them.

## Consequences

**An operator's decision survives payment-service being down.** That is the whole reason. A
synchronous call fails if the target is restarting, and the operator is left having clicked a button
that did nothing to a payment that is still held — with no obvious way to tell whether to click it
again. The event sits in the topic and is retried until it lands.

**The release path is idempotent by construction.** `applyScreeningOutcome` acts only on a payment
whose `fraud_status` is `HELD`; everything else is a no-op. So a redelivered event cannot publish
`payment.created.v1` twice, which would route the same payment to an acquirer twice.

**Every consumer of the topic sees traffic it does not care about.** Publishing all decisions rather
than only resolutions means payment-service processes an event per payment for no effect. Accepted
because the alternative is two mechanisms — synchronous for the immediate case, asynchronous for the
reviewed one — and two mechanisms for one outcome is two things that can disagree.

**The release is not instant.** Relay poll plus consumer lag, so around a second. Nobody is waiting
on it: the merchant's customer left the checkout when the payment was held.

**A blocked payment's completion event refers to nothing.** Payments blocked at creation are never
persisted, so payment-service receives an event for a payment id it has no row for. That is expected
and logged at debug, not treated as an error.

## Alternatives considered

**An HTTP callback from fraud-service.** Simpler to read and immediate. Rejected because the failure
mode is silent and lands on the operator: a released payment that stayed held, with nothing to
indicate the release was lost.

**payment-service polls for resolutions.** No coupling in either direction. Rejected because it
means every payment-service replica polls fraud-service forever to discover the handful of reviews
that are ever resolved, and the polling interval becomes the release latency.
