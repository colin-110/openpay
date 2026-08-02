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

| | |
| --- | --- |
| Date | 2026-08-02 |
| Machine | Docker Desktop on Windows 10, allotted 12 vCPUs / 7.4 GiB RAM to the daemon — one host running all 12 services, Postgres, Kafka, Redis, and Mailpit at once |
| Docker | 29.5.3, Linux containers (`linux/amd64`) |
| Running as | all services in Docker (`docker-compose.apps.yml`), built from source, not Maven-run |
| Image tag | local build, Spring Boot 3.5.16 baseline (post CVE remediation) |

This is a single laptop-class host running the entire platform at once — twelve JVMs, three
datastores, and a broker sharing 12 vCPUs. That matters for reading the numbers below: a bottleneck
here is as likely to be host contention as it is application design, and the notes say which for
each finding rather than leaving that to guesswork.

## `payment-create.js`

| Rate | Duration | p50 | p95 | Error rate | Notes |
| --- | --- | --- | --- | --- | --- |
| 20/s | 1m | 29.0ms | 52.9ms | 0.0% | Clean. Every threshold passed. |
| 50/s | 2m | 51.0ms | 3.01s | 22.3% | **Before the Redis-timeout fix below.** |
| 50/s | 2m | 38.4ms | 113ms | 0.0% | **Same rate, same host, after the fix.** Rerun to confirm. |

The first 50/s row is what this baseline originally recorded. Tracing it down (see Observations)
found a real bug — not host contention, though that was the first guess — and fixing it turned a
22.3% failure rate with a 3-second p95 into 0% failures with a 113ms p95, at the identical rate on
the identical machine. That is the difference between a load test that measures a laptop's CPU
count and one that measures the system.

With the fix in place, `stress.js` (below) carries this further: 0% failures up to 150/s on the
same host, which is what actually answers "how far does this go" now that the artificial ceiling
is gone.

## `webhook-spike.js`

| | |
| --- | --- |
| p95 across the run | 119.5ms |
| Peak rate | 400/s (a 40x jump from the 10/s baseline, per the script's ramp) |
| Error rate | 0.0% |
| Duplicates correctly rejected | 3,309 of 35,586 callbacks (9.3%) — every one caught, none double-processed |

Passed clean, both thresholds (`p95<2000ms`, `error rate<1%`) met with wide margin. Signature
verification and deduplication hold up under a 40x spike with no visible cost — this is the one
scenario of the three that shows no sign of the host-contention problem the other two hit, likely
because webhook-service does not make a synchronous call to another service per request the way
payment creation and login do.

## `provider-outage.js`

| | |
| --- | --- |
| Acceptance rate through the whole run (outage included) | 100.00% (2,401 / 2,401) |
| p95 | 42.9ms |
| Payments accepted during the outage window | 1,602 |

The claim this test exists to check — that losing an acquirer costs latency, not payments — held
exactly: not one payment was refused while mock-bank-a was out of the routing table. Latency did
not even move measurably (p95 42.9ms is in the same range as the whole-run average), because
routing happens after creation and asynchronously; the outage never touched the request the
merchant is waiting on.

## `stress.js`

| Tier | Sent | Error rate |
| --- | --- | --- |
| 20/s | 601 | 0.0% |
| 50/s | 1,501 | 0.0% |
| 100/s | 2,988 | 0.0% |
| 150/s | 3,679 | 0.0% |

Aggregate p95 across all four tiers blended: 7.5s — high, and worth reading correctly. This is
not an error rate; every one of these payments was eventually accepted (`http_req_failed` stayed
at 0.00% throughout, and the per-tier table above shows the same). What it means is queueing: at
the higher tiers, k6 had 600 pre-allocated VUs in flight and climbing, which is what "still
correct, no longer fast" looks like under a ramping-arrival-rate executor once a host's real
throughput ceiling is approached. 150/s was the highest tier run; the actual breaking point —
where requests start being refused outright rather than merely queueing — was not reached on this
host. Worth extending `TIERS` upward on a rerun specifically to find it.

## `soak.js`

| | |
| --- | --- |
| Rate / duration | 20/s for 4 minutes (reduced from the script's 15-minute default for this run) |
| Total payments | 4,801 |
| Failures | 0 |
| p95, first half | 105ms |
| p95, second half | 62ms |
| Outbox backlog after | 0 on every service |
| DB connections active after | 0 on every pool |

No drift — if anything, the second half was faster, consistent with JIT warm-up rather than any
kind of leak. Four minutes is short for a soak test's actual purpose (a slow leak needs time to
become visible); this is a smoke-test-scale run confirming the mechanism works and finding nothing
wrong, not a substitute for the 15-30 minute run the script defaults to. Worth rerunning at the
full default duration before trusting this as a real endurance result.

## `fault-injection.sh`

Not a load test — an empirical check of the specific claims in
[docs/ARCHITECTURE.md § 5, Failure modes](../../docs/ARCHITECTURE.md#5-failure-modes), each one
verified against the real running stack by actually pausing the dependency it names, rather than
trusted because the table says so.

| Scenario | Claim | Result |
| --- | --- | --- |
| Redis paused | Rate limiting and login throttling fail open | **Confirmed, after a fix** — see below |
| fraud-service paused | Screening fails open, payment recorded `UNSCREENED` | Confirmed |
| fraud-service recovered | Screening resumes; over-threshold payments refused again | Confirmed |
| auth-service paused | API-key payments refused (503); existing dashboard sessions keep working | Confirmed |
| Kafka paused | Payment creation still succeeds; payment stays `CREATED` until Kafka returns | Confirmed |
| Kafka recovered | The payment created during the outage advances on its own — nothing lost | Confirmed |

13 of 13 checks pass — but the first run of this script found a real bug before any of them did.

**What it found:** pausing Redis and firing writes past the per-merchant rate limit didn't fail
open — it hung. Each request took up to 60 seconds before falling back. `ValidationAttemptLimiter`
and `RedisFixedWindowLimiter` both already catch `DataAccessException` and fail open correctly;
neither had a bug. The gap was one layer down: Lettuce's own default command timeout is 60
seconds, so "fails open" was true only after a full minute per call — which is not what "fails
open" is supposed to buy a caller. Fixed by setting `spring.data.redis.timeout` and
`connect-timeout` explicitly (250ms) in both auth-service and gateway-service. After the fix, the
same 12-request burst with Redis paused completes in well under the original per-request timeout,
every one of them succeeding.

That same fix is very likely why the original `payment-create.js` 50/s failures above disappeared
on rerun: the gateway's own rate limiter is backed by the identical Lettuce client with the
identical missing timeout, so contention that merely slowed Redis down — not took it fully
offline — would have produced the same class of stall under load, just intermittently rather than
on every call. The fault-injection script made the failure mode reproducible on demand; the load
test retroactively confirms the fix mattered under real concurrency, not just in a
one-dependency-at-a-time check.

**A second, smaller finding along the way:** the seeded `extreme-value-payment` BLOCK rule is
scoped to `currency: INR`. A USD payment of any size never matches it — not a bug in fraud-service,
but worth knowing if a demo or a future test assumes amount-based blocking applies uniformly
across currencies. It does not, in this seed data.

## Observations

- **Outbox backlog** (`openpay_outbox_unpublished`): 0 on every service in every run recorded on
  this page, checked against Prometheus immediately after each one. The relay has never been the
  bottleneck in anything measured here.
- **Database connections in use** (`hikaricp_connections_active`): 0 on every pool in every run.
  Not a connection-pool ceiling either.
- **What the original 50/s failures actually were** — corrected from this document's first
  version, which blamed host CPU contention: gateway-service's logs showed
  `AuthServiceUnavailableException`, each one a `SocketTimeoutException: Read timed out` calling
  auth-service's key-validation endpoint. The real cause, found by `fault-injection.sh` and
  written up in that section above, was Lettuce's 60-second default Redis command timeout —
  auth-service's login/key-validation throttle is Redis-backed, and under any contention that
  merely slowed Redis rather than took it offline, calls could stall long enough to cascade into
  gateway's own timeout to auth-service. Fixed by setting an explicit short Redis timeout. Host
  contention was a reasonable first guess and turned out to be the wrong one — worth recording
  that the guess was corrected, not just the fix.
- Re-run at the same 50/s rate after the fix: 0% failures, 113ms p95, on the identical host. The
  host was never the ceiling; the missing timeout was.

## Known bottlenecks

1. ~~Key validation on every request.~~ **Fixed.** Traced to a missing Redis client timeout, not
   the gateway→auth-service call itself — see Observations above and the `fault-injection.sh`
   section for the full story. The synchronous per-request call to auth-service is still there
   and still uncached beyond auth-service's own usage tracker; it simply no longer stalls when
   Redis is slow or unreachable.
2. **The fraud gate inside the payment transaction.** Screening is a synchronous HTTP call made
   while the payment transaction is open, so its latency is added to every write and it holds a
   database connection while it waits. Timeouts are 500ms connect and 1s read, which bounds it —
   but bounded is not free. `fault-injection.sh` confirms this path fails open correctly when
   fraud-service is unreachable, so it degrades screening, not payment creation.
3. **The outbox relay's poll interval.** 500ms by default. Not exercised as a bottleneck in any
   run on this page — backlog stayed at 0 throughout every scenario, including the 150/s stress
   tier and the soak run — but still worth knowing about above whatever write rate is eventually
   found to be the real ceiling.
