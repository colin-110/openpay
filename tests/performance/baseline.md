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

## `stress.js` — run of 2026-08-03, after the merchant-pool and warm-up fixes

The first measurement taken with every payment actually being a payment. Default tiers, 45s each,
45s warm-up ramped to the top tier, merchant pool sized from the rate, **stock
`RATE_LIMIT_PER_WINDOW=30`** (no longer raised — see
[the perf README](README.md#stressjs--finding-where-it-actually-breaks)).

Three runs of the identical script, taken while chasing an unrelated broker fault. The failure rate
per tier:

| Tier | Run A | Run B | Run C |
| --- | --- | --- | --- |
| 20/s | 0.0% | 0.0% | 0.0% |
| 50/s | 0.0% | 0.0% | 0.0% |
| 100/s | 10.0% | 0.0% | **9.7%** |
| 150/s | 41.4% | 4.6% | **0.0%** |

**Up to 50/s this host is reliably clean. Above it, no number here is worth quoting.** Run C had
150/s passing perfectly while 100/s failed 9.7% — the tiers invert, which cannot be a property of
the platform and is the signature of the measurement being swamped by the host. Twelve vCPUs are
running twelve JVMs, Postgres, Kafka, Redis and the load generator's own container, and above about
50 requests a second the dominant variable is which of those the scheduler favours that minute.

So the honest reading of this table is a floor, not a ceiling: **at least 50 payments a second,
sustained, with zero failures and p95 well inside the threshold.** Where it actually breaks is not
knowable from this machine, and the earlier draft of this section — which read "the real ceiling is
around 100 payments a second" — was drawn from a single run and should not have been stated. That
is the same mistake as the original baseline, made again with better inputs.

Anything firmer needs the k6 scripts pointed at the Kubernetes manifests on hardware that is not
also running the system under test. Nothing in the code changes for that; only the number does.

For what it is worth, at 150/s payment-service's connection pool was observed at its maximum of 25
with acquisition timeouts recorded, so the write path's pool is a plausible first constraint to
look at when someone does run this properly. Nine services share one PostgreSQL, so raising it is a
trade rather than a free win.

### The auth-cache regression this run found

The first attempt at this table failed 10% at 100/s and **41.4% at 150/s**, with 1,089 gateway
`validate-key` read timeouts. That was a bug introduced by the stale-while-revalidate fix itself:
its background refresh executor had two threads, which is ample for the one merchant the load tests
used to drive and hopelessly undersized for a realistic pool. Refresh work scales with the number
of distinct **keys**, not with the request rate — 126 merchants on a 5-second TTL need a refresh
roughly every 40ms. The queue backed up, entries aged past the stale grace faster than they could be
renewed, and requests fell through to the synchronous path, where they hit the read timeout and the
gateway, failing closed, turned them into refused payments.

Fixed by sizing the executor for key count and renewing at 75% of the TTL rather than waiting for
expiry, so under steady traffic an entry is never served stale at all. Same run afterwards: 100/s
went to 0.0% failures and 150/s to 4.6%, and gateway auth failures fell from 1,089 to 492.

Worth recording rather than quietly fixing, because both halves are the same lesson: **every test in
`CachingAuthServiceClientTest` used a single API key, and every load scenario used a single
merchant.** A fleet of one hides whole categories of bug, and it hid this one in two places at once.

## `payment-create.js`

> [!WARNING]
> **Everything recorded below predates two fixes, and both of them change what these numbers mean.
> Do not compare a current run against this section — re-record it first.**
>
> 1. **Every run below drove a single merchant**, so after roughly the first hundred payments the
>    seeded `merchant-velocity-burst` rule held the rest for review. A held payment skips the
>    `PAYMENT_CREATED` publish entirely — no routing, no acquirer, no ledger — and still returns
>    201. The great majority of the "payments" in these tables therefore did substantially less
>    work than a payment does, which means these figures **flatter** the platform.
> 2. **Every run below started cold.** Each row is described as a fresh run against a stack rebuilt
>    from source, and the ordering artefact that produces is large enough to invert the results —
>    see [The knee was an artefact](#the-knee-was-an-artefact) below.
>
> Both are fixed in the scripts (a merchant pool, a warm-up stage, and a threshold that fails a run
> if anything is held). The account below is kept rather than deleted because the reasoning in it —
> the threshold bug, the Redis timeout — is still correct and still worth reading.

### The knee was an artefact

The claim below is that the knee is between 50 and 100 requests a second. It is not, and the
evidence is three runs of **the same 50/s tier** against the same stack, differing only in what ran
before them:

| State before the run | p50 | p95 | p99 | Achieved | Errors |
| --- | --- | --- | --- | --- | --- |
| Cold stack, second tier after 20/s | 22ms | **3637ms** | **6130ms** | 46.9/s | 0.00% |
| Immediately after a full stress run | 14ms | **25ms** | **35ms** | 50.0/s | 0.00% |
| After ~100 seconds idle (JIT still hot) | 15ms | 103ms | **2721ms** | 49.0/s | 0.05% |

A 145× spread in p95 at one rate. The third row is the important one: with the JVMs still hot, the
tail cliff comes back after a couple of minutes of quiet, so this is not a one-time startup cost —
it re-arms whenever traffic stops, which is what happens after a deploy, a scale-out, or overnight.

It also produced real refusals rather than just slow responses. The 0.05% were gateway →
auth-service `validate-key` read timeouts, and the gateway fails closed, so a legitimate payment
was refused. Root cause was a cache stampede: `CachingAuthServiceClient` had a 5-second TTL and no
request collapsing, so at expiry every in-flight request for a key missed together and hit the one
service every request must consult. Fixed by serving stale while a single background refresh runs.

With a warm-up stage in front, the tiers behave the way a queueing ceiling should — 20/s at 49ms
p95 and 50/s at 46ms p95, monotonic and boring.

### Run of 2026-08-03 — with p99, and with the threshold bug fixed

Every row below is a fresh 60-second `constant-arrival-rate` run on the machine described above,
against a stack rebuilt from source, with the per-merchant rate limit raised (see
[A note on the rate limit](#a-note-on-the-rate-limit)).

| Target | Achieved | Payments | p50 | p95 | p99 | max | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 20/s | 19.99/s | 1,201 | 55.9ms | 227ms | 443ms | 678ms | 0.00% |
| 50/s | 49.95/s | 3,000 | 43.0ms | 184ms | 295ms | 494ms | 0.00% |
| 100/s | 97.77/s | 5,884 | 105ms | **1.89s** | **3.00s** | 4.72s | 0.00% |

**The knee is between 50 and 100 requests a second on this host.** — *This conclusion is now known
to be wrong; see [The knee was an artefact](#the-knee-was-an-artefact) above. The reasoning that
follows about latency-versus-errors is still sound, and is why it is left here.* Note what does
*not* happen at 100/s: nothing fails. `http_req_failed` is 0.00% across all three rows — every payment is still
accepted and still correct. What degrades is latency, by an order of magnitude, plus 119 iterations
that k6 could not start on schedule at all. That distinction matters: this is a queueing ceiling,
not a correctness failure, and a test that only watched the error rate would have called the 100/s
run a clean pass.

50/s is *faster* than 20/s (p95 184ms vs 227ms), which is JIT warm-up rather than anything
mysterious — the 20/s run was first after a restart and paid for the compilation.

**These numbers caught a bug in the test, not just in the system.** The threshold on the
latency trend was written as `payment_create_duration{expected_response:true}`, but
`expected_response` is an HTTP system tag k6 only attaches to `http_req_*` metrics — never to a
custom `Trend`. That submetric therefore matched zero samples, reported `0s`, and passed
`p(95)<1000` on every run that had ever been recorded here. Corrected to an untagged
`payment_create_duration`, it immediately failed the 100/s row (`✗ p(95)=1.89s`) — a real
regression the old form reported as green. A threshold that cannot fail is worse than no
threshold, because it reads like coverage.

### Earlier run — the Redis timeout fix

| Rate | Duration | p50 | p95 | Error rate | Notes |
| --- | --- | --- | --- | --- | --- |
| 20/s | 1m | 29.0ms | 52.9ms | 0.0% | Clean. Every threshold passed. |
| 50/s | 2m | 51.0ms | 3.01s | 22.3% | **Before the Redis-timeout fix below.** |
| 50/s | 2m | 38.4ms | 113ms | 0.0% | **Same rate, same host, after the fix.** |

The first 50/s row is what this baseline originally recorded. Tracing it down (see Observations)
found a real bug — not host contention, though that was the first guess — and fixing it turned a
22.3% failure rate with a 3-second p95 into 0% failures with a 113ms p95, at the identical rate on
the identical machine. That is the difference between a load test that measures a laptop's CPU
count and one that measures the system.

### A note on the rate limit

The platform's per-merchant write limit is 30 requests per 5-second window (~6/s), so every rate
on this page is far above it. All the runs recorded here raise it via `RATE_LIMIT_PER_WINDOW`,
because the question being asked is "how fast is the write path", not "does the rate limiter
work" — there are separate tests for the latter.

That override did not actually work until 2026-08-03. `docker-compose.apps.yml` never forwarded
`RATE_LIMIT_PER_WINDOW` into gateway-service's environment, so setting it on the compose command
line — exactly as `tests/performance/README.md` instructed — changed nothing inside the container.
Fixed by forwarding it explicitly. Worth recording because the failure was silent in the worst
way: the documented instruction looked like it worked, and any run that trusted it was measuring
the limiter rather than the write path.

### What these runs are actually exercising

At any sustained rate above ~1.6/s from a single merchant, the seeded `merchant-velocity-burst`
rule (100 payments per 60s → REVIEW) fires, and most payments come back `HELD` rather than
`ALLOWED`: 1,101 of 1,201 at 20/s, 5,783 of 5,884 at 100/s. That is the fraud engine working
correctly, not a defect, and the write path is the same either way — screening is called, the
payment and its outbox row are committed together. But it does mean these are latencies for the
*held* path, and a run spread across many merchants would sit mostly on the `ALLOWED` path
instead. Fixing that needs multi-merchant setup, which is listed as a known gap in the main
README's limitations.

## `webhook-spike.js`

### Run of 2026-08-03

| | |
| --- | --- |
| Callbacks handled | 35,515 |
| Peak rate | 400/s (a 40x jump from the 10/s baseline, per the script's ramp) |
| p50 / p95 / p99 | 27.1ms / 913.8ms / 1.20s |
| Error rate | **0.00%** (0 of 35,515) |
| Checks passed | 100.00% (35,515 of 35,515) |
| Duplicates correctly rejected | **3,411** — every one caught, none double-processed |
| Dropped iterations | 395 |

Passed both thresholds (`p95<2000ms`, `error rate<1%`). The 395 dropped iterations are the spike
briefly outrunning the VU pool at the top of the ramp; nothing that was sent failed.

Worth stating plainly because it is the property that matters most here: **3,411 duplicate
callbacks arrived during a 40× spike and not one of them was processed twice.** A duplicate
capture that slipped through would credit a merchant for money that arrived once, and dedup is
precisely the check most likely to degrade quietly under load.

### Earlier run

| | |
| --- | --- |
| p95 across the run | 119.5ms |
| Peak rate | 400/s |
| Error rate | 0.0% |
| Duplicates correctly rejected | 3,309 of 35,586 callbacks (9.3%) |

Passed clean, both thresholds (`p95<2000ms`, `error rate<1%`) met with wide margin. Signature
verification and deduplication hold up under a 40x spike with no visible cost — this is the one
scenario of the three that shows no sign of the host-contention problem the other two hit, likely
because webhook-service does not make a synchronous call to another service per request the way
payment creation and login do.

## `provider-outage.js`

### Run of 2026-08-03

| | |
| --- | --- |
| Acceptance rate through the whole run (outage included) | **100.00% (2,401 / 2,401)** |
| p50 / p95 / p99 | 28.8ms / 58.5ms / 95.6ms |
| max | 420.9ms |
| Error rate | 0.00% (0 of 2,406) |

Reproduced exactly: an acquirer was disabled in the routing table forty seconds into a two-minute
run at 20/s, and **not one payment was refused.** Latency did not move either — p95 58ms is
ordinary for this host — because creation never touches an acquirer. Routing happens afterwards
and asynchronously, so the outage never touched the request the merchant was waiting on.

### Earlier run

| | |
| --- | --- |
| Acceptance rate through the whole run | 100.00% (2,401 / 2,401) |
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

### Run of 2026-08-03 — four tiers, capped VUs, and a result that corrects the one above

| Tier | Sent | Achieved | Failed | p50 | p95 | p99 |
| --- | --- | --- | --- | --- | --- | --- |
| 20/s | 900 | 20.0/s | 0.0% | 80ms | 659ms | 947ms |
| 50/s | 2,251 | 50.0/s | 0.0% | 48ms | 90ms | 160ms |
| 100/s | 4,500 | 100.0/s | 0.0% | 46ms | **98ms** | 250ms |
| 150/s | 6,750 | 150.0/s | 0.0% | 49ms | 220ms | 341ms |

Dropped iterations: **0**. Every tier hit its requested rate exactly, and nothing failed anywhere.

**This disagrees with the `payment-create.js` table above, and the disagreement is the finding.**
That table records p95 = 1.89s at 100/s; this one records 98ms at the same rate on the same host,
twenty times better. Both runs really happened. The difference is how they were scheduled:

- The `payment-create.js` rows were three 60-second runs back to back with no gap. The 100/s run
  started while the platform was still draining the 50/s run that ended seconds earlier — outbox
  rows still relaying, connections still cycling.
- `stress.js` leaves a deliberate 10-second gap between tiers so in-flight work from tier N drains
  before tier N+1 starts, which is exactly what that gap is in the script for.

The 20/s tier here is the same effect in miniature and in the other direction: it is the *worst*
tier on the page (p95 659ms) purely because it ran first, on cold JVMs, and paid for JIT
compilation that every later tier inherited for free.

The corrected reading, then: **this host sustains 150 payments/second at p95 220ms / p99 341ms
with zero failures and zero dropped iterations, once warm.** The earlier "knee between 50 and
100/s" was measuring back-to-back scheduling, not a capacity limit. Both numbers are left on this
page rather than the inconvenient one deleted, because "your load generator's schedule changed the
answer by 20x" is worth more to a future reader than a single tidy figure.

The real ceiling was still not found — 150/s was the top tier and it was clean.

### 2026-08-03: what happens above 150/s, and why the tiers are capped now

An attempt to find the true breaking point with `TIERS=50,100,200,300,450,600` did not produce a
number — it produced an outage. Above roughly 150/s the write path's latency exceeds the arrival
interval, so `constant-arrival-rate` allocates virtual users faster than they retire. With the
original `maxVUs: rate * 4` there was nothing to stop that: the run passed a thousand concurrent
VUs, the Docker daemon's control plane started returning HTTP 500 to every command, and the run
had to be killed. The containers themselves stayed up and kept serving — the gateway answered
`/actuator/health` in 0.58s throughout — but `docker ps` did not respond for minutes.

Two things came out of that, both now in the script:

- **`MAX_VUS` (default 300) caps concurrency per tier.** Saturation now surfaces as
  `dropped_iterations` in the summary rather than as unbounded VU growth. Same finding, without
  losing the run to get it.
- **The default top tier is 150, not 250.** Not timidity — 150/s is simply past this host's knee
  already, and tiers beyond it on this hardware measure the load generator and the container
  runtime rather than the platform.

The honest summary of the ceiling on this machine: **clean to 50/s, degrading by 100/s, and past
~150/s the single-host test rig is the thing that breaks first.** Finding the architecture's own
ceiling needs the Kubernetes manifests on real nodes, which is listed as a known limitation rather
than claimed as a result.

### Earlier run — four tiers, before the cap

Aggregate p95 across all four tiers blended: 7.5s — high, and worth reading correctly. This is
not an error rate; every one of these payments was eventually accepted (`http_req_failed` stayed
at 0.00% throughout, and the per-tier table above shows the same). What it means is queueing: at
the higher tiers, k6 had 600 pre-allocated VUs in flight and climbing, which is what "still
correct, no longer fast" looks like under a ramping-arrival-rate executor once a host's real
throughput ceiling is approached. 150/s was the highest tier run; the actual breaking point —
where requests start being refused outright rather than merely queueing — was not reached on this
host. Worth extending `TIERS` upward on a rerun specifically to find it.

## `soak.js`

### Run of 2026-08-03

| | |
| --- | --- |
| Rate / duration | 25/s for 4 minutes |
| Payments | 6,000 |
| p95, first half | 90ms |
| p95, second half | **82ms** |
| Failures | 0 in either half |

No drift — the second half was marginally *faster*, which is JIT settling rather than anything
suspicious. The script's own drift threshold (`second_half p(95) < 1200ms`) passed with a wide
margin.

Four minutes is still far too short to be evidence about a slow leak, and that limitation is
unchanged from the earlier run below. This confirms the mechanism works and finds nothing wrong;
it is not a substitute for the multi-hour run the scenario is actually for.

### Earlier run

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

**13 of 13 checks pass** (re-run 2026-08-03 on a freshly rebuilt stack) — but the runs that got
there found three separate bugs, two of them in this script rather than in the platform.

**The script's own client timeout made one assertion unobservable.** `code()` and `body()` used
`curl --max-time 8`, while the gateway's read timeout to auth-service is 10 seconds. `docker
pause` freezes a process without closing its sockets, so a request made while auth-service is
paused hangs for the full 10s before the gateway gives up and answers 503 — and curl abandoned it
at 8s, reporting `000`. The "API keys fail closed" check could therefore never see the response it
existed to assert. Raised to 20s: any client timeout here has to outlast the longest server-side
timeout being exercised.

**A run against a non-default configuration is not a run.** One attempt reported 10/13 purely
because the stack still had `RATE_LIMIT_PER_WINDOW=200000` left over from a load test, so the
check that fires 12 writes "past the normal rate limit" was not past anything. Worth stating
because the failure looked like a regression and was an environment artifact — the fix is to reset
config before injecting faults, not to loosen the assertion.

**And one lesson that is not the script's fault:** editing this file while it is running corrupts
the run. Bash reads a script lazily by byte offset, so an edit mid-execution shifts everything
after the cursor (`ision_merchant: command not found` is what that looks like). Run it from a copy
if it needs changing while in flight.

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
- **The bug none of these tests could ever have found.** Every script on this page drives sustained
  load, and load is precisely the condition under which the following cannot happen — so it took
  reading the payments table of a stack that had merely been left alone. Taken from a three-hour-old
  deployment:

  | Gap before the payment | `fraud_status` |
  | --- | --- |
  | 33s after start | `UNSCREENED` |
  | 40s (traffic flowing) | `ALLOWED` |
  | 2m 24s idle | `UNSCREENED` |
  | seconds apart | `ALLOWED` ×6 |
  | 3h 02m idle | `UNSCREENED` |

  Three for three: the first payment after an idle gap skipped risk screening entirely. Screening
  answers in ~35ms warm, but the path goes cold after roughly two minutes of quiet and overruns
  payment-service's 1s read timeout — which **fails open**, so the result is not an error but a
  captured payment recorded as never checked. `ScreeningWarmUp` already existed and covered exactly
  one case, cold *start*; the far larger case was cold *idle*, and it left the startup fix looking
  effective while the window was open essentially all the time. Fixed by running the same warm-up on
  a 30s timer — inside HikariCP's 60s `idle-timeout` and the caller's 30s connection eviction.

  Verified the way it was found, by idling rather than by loading: four separate 200s+ idle windows
  after the fix, each followed by one payment. Worth stating plainly as a gap in this page —
  a performance suite that only ever measures a busy system is blind to every failure that needs
  quiet, and this platform will spend most of its life quiet.

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
