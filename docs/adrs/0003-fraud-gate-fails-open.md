# ADR-0003: Risk screening fails open, and says so in the payment

**Status:** Accepted

## Context

Every payment is screened by a synchronous call to fraud-service before it is written. That call can
fail: the service can be down, restarting, or slower than its 1s read timeout.

What should payment-service do when it gets no answer?

**Fail closed** — refuse the payment — means one unhealthy risk service stops *every merchant on the
platform* from taking money. The component whose entire purpose is preventing losses becomes the
cause of a total outage, and the losses it prevents in that window are a rounding error next to the
revenue it just stopped.

**Fail open** — accept the payment unscreened — means a window in which fraudulent payments go
through. That is a real cost, but it is bounded by the length of the outage, and it is the kind of
cost that is insurable and reconcilable after the fact.

There is a third failure available to both: doing either one *silently*. A payment that went through
unscreened and is indistinguishable from one that passed the rules cannot be found afterwards, which
is the state you least want to be in when a chargeback arrives.

## Decision

**Fail open by default, and record it distinctly.**

A payment that could not be screened is stored with `fraud_status = UNSCREENED`, not `ALLOWED`. The
two are different claims — "we decided this was fine" and "nobody looked" — and only one of them
should turn up in a dispute. It is on the API response, it is a tag on
`openpay_payments_accepted_total`, and it is a series on the Payment Flow dashboard.

`FRAUD_FAIL_OPEN=false` reverses it for a deployment that would rather stop taking payments, in
which case the merchant gets a `503 screening_unavailable` — retryable, because the payment may well
be fine once screening is back.

An outcome the client does not recognise is treated the same as no answer at all, rather than
guessed at.

Timeouts are 500ms connect and 1s read — tighter than any other call in the codebase, because this
one is in the write path of every payment. A gate that takes two seconds to answer has already cost
more than it saves.

## Consequences

**There is a window where unscreened payments are accepted.** That is the trade, stated plainly. It
is visible in the data and on the dashboard rather than inferred.

**The call happens inside the payment transaction.** It holds a database connection while it waits,
which the tight timeouts bound but do not eliminate. Under a fraud-service slowdown, connection pool
pressure will appear before anything else does — which is why pool usage is a panel on the Service
Health dashboard.

**`fraud-service` runs two replicas and has a tighter HPA target than anything else** (60% rather
than 70%). Because the failure mode is failing open rather than erroring, a slow fraud-service does
not show up as errors — it shows up as unscreened payments, which nothing pages on.

## Alternatives considered

**Screen asynchronously, after acceptance.** No latency in the write path, no failure mode at all.
Rejected because a BLOCK would then arrive after the payment had already been routed to an acquirer,
which means the fraud decision is a reversal rather than a refusal — and reversing a payment costs
real money that refusing it does not.

**Queue unscreened payments for later screening.** Keeps the payment and screens it when the service
returns. Rejected as the worst of both: the payment is held, so the merchant's customer is waiting
anyway, and the queue has to be drained by something that also might be down.
