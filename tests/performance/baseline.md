# Performance baseline

Empty on purpose. A baseline copied from someone else's machine is a number that means nothing —
worse, it is a number people will compare against and draw conclusions from.

Run the scenarios on the hardware you care about, fill this in, and date it. A baseline is only
useful as a thing to compare a later run against, so what matters is that it was measured somewhere
you will measure again.

## How to record one

```bash
docker compose -f platform/docker/docker-compose.yml -f platform/docker/docker-compose.apps.yml up -d --build
```

Let it settle for a minute — the JVMs are at their slowest immediately after start, and a baseline
taken during warm-up flatters every later run.

```bash
k6 run --summary-export=baseline-payment-create.json tests/performance/payment-create.js
```

## Environment

Record enough that a later run can be compared honestly, and enough that an unfair comparison is
obvious.

| | |
| --- | --- |
| Date | |
| Machine | CPU, cores, RAM |
| Docker | version, and how much memory the daemon is allowed |
| Running as | all services in Docker / services from Maven |
| Image tag | |

## `payment-create.js`

| Rate | Duration | p50 | p95 | p99 | Error rate | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| 50/s | 2m | | | | | |
| 100/s | 2m | | | | | |
| 200/s | 5m | | | | | |

Note where it stops being linear. The number worth having is not the best throughput observed, it
is the rate above which latency starts climbing without throughput following.

## `webhook-spike.js`

| | |
| --- | --- |
| p95 at 10/s | |
| p95 at 400/s | |
| Recovery time after the spike | |
| Duplicates correctly rejected | |

## `provider-outage.js`

| | |
| --- | --- |
| Acceptance rate before the outage | |
| Acceptance rate during the outage | |
| p95 before | |
| p95 during | |
| Time from disabling the rule to the first failover | |

## Observations

Where the time went, and what the dashboards showed while it was happening. Two things worth
checking every time, because both are invisible in a k6 summary:

- **Outbox backlog** (`openpay_outbox_unpublished`). If this grew and did not come back down, the
  relay could not keep up with the write rate — and k6 will have reported a perfectly healthy run,
  because every payment was accepted. They just stopped advancing.
- **Database connections in use** against the pool maximum. A pool pinned at its limit is the usual
  reason latency climbs everywhere at once, and it looks like a slow application rather than a
  configuration ceiling.

## Known bottlenecks

Fill this in from what you find rather than from what you expect. Candidates worth checking first,
in the order they are likely to bite:

1. **The fraud gate inside the payment transaction.** Screening is a synchronous HTTP call made
   while the payment transaction is open, so its latency is added to every write and it holds a
   database connection while it waits. Timeouts are 500ms connect and 1s read, which bounds it —
   but bounded is not free.
2. **The outbox relay's poll interval.** 500ms by default. Below that the relay is polling more
   than it is publishing; above it, the backlog is what it takes to drain a burst.
3. **Key validation on every request.** The gateway calls auth-service per request, cached only by
   auth-service's own usage tracker. That is one extra network hop on the hot path of everything.
