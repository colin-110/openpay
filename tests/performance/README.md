# Load and resilience tests

Three scenarios, in [k6](https://k6.io). They run against a live stack — there is no mocking here,
because the interesting numbers all come from parts a mock would remove.

```bash
docker compose -f platform/docker/docker-compose.yml -f platform/docker/docker-compose.apps.yml up -d --build
```

```bash
k6 run tests/performance/payment-create.js
```

`OPENPAY_ADMIN_TOKEN` must be set: every scenario onboards its own throwaway merchant in `setup()`,
and admin endpoints fail closed without it. A run with no credential would produce a beautiful
latency curve for 401 responses, which is worse than no number at all — so the scripts refuse to
start instead.

## The scenarios

### `payment-create.js` — sustained write load

The path that matters: authenticate at the gateway, screen for risk, persist, and append to the
outbox, all in one transaction.

Through the gateway rather than straight to payment-service, on purpose. The gateway's key
validation is a network call to auth-service on every request, and a test that skipped it would be
measuring a system nobody runs.

**Arrival rate, not virtual users.** A VU-based test slows its own request rate when the system
slows down, which hides exactly the thing being measured. Real merchants keep sending at their own
pace whether or not the platform is keeping up.

```bash
k6 run -e RATE=200 -e DURATION=5m tests/performance/payment-create.js
```

Amounts stay under the seeded risk thresholds. A run that wandered over 50,000 rupees would start
holding payments for review, and a run over 500,000 would start being refused outright — either
would make the numbers look excellent for entirely the wrong reason.

### `webhook-spike.js` — a cliff, not a ramp

The realistic shape for acquirer callbacks is a spike: an acquirer that queued callbacks during an
outage delivers all of them the moment it reconnects. This goes from 10/s to 400/s in ten seconds
and holds.

webhook-service has to verify an HMAC and check for a duplicate on every one, and neither is work it
can skip under pressure. Every tenth iteration deliberately re-sends an event it has already sent,
because deduplication is the check most likely to quietly degrade under load — and a duplicate
capture that got through would credit a merchant twice.

The threshold here is looser than the payment path's, deliberately: a callback is a machine that
will retry, not a customer watching a spinner.

### `provider-outage.js` — the claim, tested

This platform claims that losing one acquirer costs latency, not payments. That is worth believing
only if it has been watched happening.

The test runs steady load, disables `mock-bank-a` in the routing table forty seconds in, and asserts
that acceptance stays above 99.9% while latency is allowed to move. Both halves matter: creation
never touches an acquirer, so acceptance should not move at all — but failover means trying one
acquirer, waiting for it to fail, and trying the next, and asserting that costs nothing would be
asserting something false.

The rule is restored in `teardown()`, including after a failed run. A test that leaves an acquirer
disabled has broken the environment for whoever runs next, and they would have no reason to suspect
it.

## Thresholds

| Metric | Threshold | Why that number |
| --- | --- | --- |
| `payment_create_duration` p95 | < 1s | Above a second, a customer is watching a spinner |
| `http_req_failed` | < 1% | Anything higher is a real failure, not noise |
| webhook p95 under spike | < 2s | A callback retries; a customer does not |
| `payment_acceptance_rate` during outage | > 99.9% | Losing an acquirer must not lose a payment |

p95, never an average. An average latency hides the tail, and the tail is what a customer at a
checkout actually experiences.

## Reading a run

Watch Grafana while it runs — [Payment Flow](http://localhost:3000) is the dashboard for it. Two
panels say more than the k6 summary does:

- **Outbox backlog.** If this climbs and stays up, the relay is behind. Payments are still being
  accepted and none of them are advancing, which k6 will report as a completely healthy run.
- **Acquirers out of rotation.** During `provider-outage.js` this should go to 1 and stay there
  until teardown.

## Recording a baseline

[`baseline.md`](baseline.md) has the shape to fill in, and it is empty on purpose: a baseline copied
from someone else's laptop is a number that means nothing. Run it on the hardware you care about and
write down what you saw.

## What this does not cover

- **Kafka throughput.** These tests drive the synchronous API, and the event backbone is exercised
  only as a side effect. Saturating the broker needs a producer aimed at it directly.
- **Database contention beyond one merchant.** Each run uses a single merchant, so the
  `(merchant_id, idempotency_key)` index is hotter than it would be with real traffic spread across
  thousands of them.
- **Sustained multi-hour runs.** Everything here is minutes. Memory leaks and connection-pool
  exhaustion tend to need hours to appear.
