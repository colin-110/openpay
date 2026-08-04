# Load and resilience tests

Five scenarios in [k6](https://k6.io), plus one dependency-failure script that isn't k6 at all.
They run against a live stack — there is no mocking here, because the interesting numbers, and the
interesting failures, all come from parts a mock would remove.

```bash
docker compose -f platform/docker/docker-compose.yml -f platform/docker/docker-compose.apps.yml up -d --build
```

```bash
k6 run tests/performance/payment-create.js
```

`OPENPAY_ADMIN_TOKEN` must be set: every scenario onboards its own throwaway merchants in
`setup()`, and admin endpoints fail closed without it. A run with no credential would produce a
beautiful latency curve for 401 responses, which is worse than no number at all — so the scripts
refuse to start instead.

## Two things every scenario now does, and why

**A pool of merchants, not one.** This is the correction to a bug that made most of the numbers
here mean something other than what they said. The seeded `merchant-velocity-burst` rule holds a
merchant's payments for review after 100 in 60 seconds, and every scenario used to drive a single
merchant — so about five seconds into a 20/s run, *every payment after that* was held. A held
payment deliberately skips the `PAYMENT_CREATED` publish, so it never routes, never reaches an
acquirer and never touches the ledger, and it returns 201, so k6 counted it as a clean success. A
measured stress run showed **17,017 payments held against 1,508 created**: 92% of the load was
exercising the review path while the summary claimed to be measuring the write path.

`merchantsForRate()` now sizes a pool so no single merchant approaches the limit, iterations are
spread across it by global iteration counter, and **every scenario fails if even one payment is
held**. Real traffic spreads across thousands of merchants and does not trip a per-merchant
velocity rule, so the fix is to look like real traffic rather than to switch the rule off.

**A warm-up stage.** Also a correction. Three runs of the *same* 50/s tier against the same stack
gave p95 3637ms as the first load step from idle, 25ms immediately after a full run, and 103ms
with a 2.7s p99 after 100 seconds idle — a 145× spread at one rate, decided entirely by what ran
before it. Without a warm-up the first tier pays for JIT compilation and connection-pool growth on
behalf of every tier after it, which is why the old baseline showed low rates as *slower* than
high ones. `stress.js` ramps to its top tier before measuring anything; `-e WARMUP=0` measures the
cold path deliberately, which is a legitimate and different question.

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

### `stress.js` — finding where it actually breaks

`payment-create.js` answers "is this rate healthy?" This answers "what rate stops being healthy,
and what does that look like?" Four separate `constant-arrival-rate` scenarios run back to back —
not one ramping executor — each tagged with its own rate via `k6/execution`'s `scenario.name`, so
the end-of-run summary breaks results down per tier instead of blending everything into one
average that hides exactly the transition this test exists to find.

```bash
k6 run tests/performance/stress.js
k6 run -e TIERS=20,50,100,150,250 -e MAX_VUS=600 -e STAGE_DURATION=1m tests/performance/stress.js
```

No thresholds abort the run early — the point is to keep going past the point where things start
failing, so a threshold breach is information in the summary, not an excuse to stop before the
interesting tier.

**`MAX_VUS` is a safety rail, not a tuning knob.** Above roughly 150/s on a single laptop host the
write path's latency exceeds the arrival interval, so an arrival-rate executor allocates virtual
users faster than they retire. Left uncapped it does not converge: a run with tiers up to 600/s
took k6 past a thousand concurrent VUs and wedged the Docker daemon, which produces no data at all
and takes the stack with it. With the cap, the same saturation shows up as `dropped iterations` in
the summary — the same finding, without losing the run to get it. Raise both together on hardware
that can take it.

**The per-merchant rate limit no longer needs raising**, and this used to say the opposite. Load is
spread across enough merchants to stay under the fraud velocity rule, which incidentally puts each
merchant at around one request a second even at the top tier — far under the stock 30 writes / 5s.
Measured: a 50/s run against the default `RATE_LIMIT_PER_WINDOW=30` passes every threshold at p95
245ms.

Worth stating plainly, because the old instruction was itself a quiet distortion: a single merchant
sending 150 writes a second is not a shape any limiter should permit, and disabling the limiter to
allow it meant the run no longer resembled traffic the platform would ever accept. If you find
yourself needing the override again, the pool is too small — that is the thing to fix.

### `soak.js` — what a short run can't see

Sustained load at a rate already known to be clean, held for a long time — not to find a breaking
rate, but to catch what only shows up given enough time: an outbox relay that keeps up
minute-to-minute but slowly falls behind, a connection that leaks once every few thousand
requests, latency that creeps rather than jumps. None of that is visible in `payment-create.js`'s
two minutes.

```bash
k6 run tests/performance/soak.js
k6 run -e RATE=20 -e DURATION=20m tests/performance/soak.js
```

The summary splits latency and failures first-half vs second-half, so drift shows up without an
external time-series backend. What it cannot measure from inside k6 — the outbox backlog and the
database connection pool — is exactly what "Reading a run" below already says to check separately;
a soak test is where those two panels matter most, because a short run's window may simply be too
narrow for either to have moved yet.

### `fault-injection.sh` — the Failure modes table, checked rather than trusted

Not k6. A bash script that pauses a real container (`docker pause`, a frozen process rather than a
stopped one) and asserts that the documented behaviour in
[docs/ARCHITECTURE.md § 5](../../docs/ARCHITECTURE.md#5-failure-modes) actually holds against the
running stack, then unpauses and asserts recovery. "Redis dies, rate limiting fails open" is a
claim in a table; this is what turns it into a fact that gets re-checked every time it runs instead
of a sentence that quietly goes stale.

```bash
OPENPAY_ADMIN_TOKEN=dev-admin-token ./scripts/fault-injection.sh
```

It already found one real bug on its first run: Lettuce's default Redis command timeout is 60
seconds, so "fails open" used to mean "hangs for a minute, then fails open." See
[baseline.md § fault-injection.sh](baseline.md#fault-injectionsh) for the full account, including
why that fix is very likely what fixed the `payment-create.js` 50/s failures above, too.

Always unpauses whatever it paused, even on failure or Ctrl-C — but nothing can trap `SIGKILL`, so
if this script is force-killed rather than interrupted normally, check `docker ps` for anything
`(Paused)` afterward and unpause it by hand.

## Thresholds

| Metric | Threshold | Why that number |
| --- | --- | --- |
| `payment_create_duration` p95 | < 1s | Above a second, a customer is watching a spinner |
| `http_req_failed` | < 1% | Anything higher is a real failure, not noise |
| `payments_held_for_review` | 0 | A held payment returns 201 without exercising the path being measured |
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

And one thing neither Grafana nor k6 will tell you, which is worth a terminal of its own during any
long run:

```bash
docker exec openpay-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group payment-service
```

**Lag that is frozen at an exact number is worse news than lag that is climbing.** Climbing means a
consumer is behind. Frozen — especially with a blank `CONSUMER-ID` — means it is not consuming at
all, and the only other symptom is payments quietly stopping at `CREATED` while every container
reports healthy and every HTTP call returns 201. That is not hypothetical; it is
[ARCHITECTURE.md § 5.1](../../docs/ARCHITECTURE.md#51-the-failure-this-table-did-not-have), and a
k6 run during it looks perfect right up until the acceptance suite fails.

## Recording a baseline

[`baseline.md`](baseline.md) has the shape to fill in, and it is empty on purpose: a baseline copied
from someone else's laptop is a number that means nothing. Run it on the hardware you care about and
write down what you saw.

## What this does not cover

- **Kafka throughput.** These tests drive the synchronous API, and the event backbone is exercised
  only as a side effect. Saturating the broker needs a producer aimed at it directly.
- **Realistic merchant cardinality.** Runs now spread across a pool sized to stay under the
  velocity rule — tens to low hundreds of merchants — rather than the one they used to use. That
  is enough to stop the rule firing and to take the heat off a single
  `(merchant_id, idempotency_key)` index slot, but it is still far short of the thousands a real
  deployment would have.
- **True multi-hour runs.** `soak.js` supports it (`-e DURATION=2h`), but `baseline.md`'s recorded
  run is 4 minutes — long enough to prove the mechanism works, not long enough to be confident
  about a leak that takes hours to show up. Worth running long before trusting it as that kind of
  proof.
- **Redis and Kafka pauses beyond a few seconds.** `fault-injection.sh` proves fail-open and
  recovery, not what a multi-minute real outage looks like under concurrent load — that is closer
  to what `stress.js` combined with a manual pause would answer, and nothing here does both at
  once yet.
