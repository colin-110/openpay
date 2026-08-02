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
| 50/s | 2m | 51.0ms | 3.01s | 22.3% | Breaks down — see below. |

`100/s` and `200/s` were not run: at 50/s the write path was already failing on a dependency, not
on its own logic (see Known bottlenecks), and pushing harder would have measured the same failure
mode more, not found a new one. Rerun both once that bottleneck is addressed — the gap is
deliberate, not a silent one.

**Where it stops being linear:** somewhere between 20/s and 50/s. At 20/s, p95 is 53ms and nothing
fails. At 50/s, 22.3% of requests fail outright (not held for review — outright failed, HTTP
`5xx`), and the requests that do succeed take up to 60x longer at the tail (p95 3.01s vs 52.9ms).
That is not the payment write path degrading gracefully under load; it is a downstream call timing
out. See below for which one and why.

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

## Observations

- **Outbox backlog** (`openpay_outbox_unpublished`): 0 on every service, checked against Prometheus
  immediately after the 50/s run. The relay kept up throughout — whatever caused the 50/s failures,
  it was not a write backlog.
- **Database connections in use** (`hikaricp_connections_active`): 0 on every pool, checked
  post-run. Not a connection-pool ceiling either.
- **What it actually was**: gateway-service's own logs for the 50/s run show 1,310
  `AuthServiceUnavailableException: Auth service returned an unexpected error`, each one caused by
  a `SocketTimeoutException: Read timed out` calling auth-service's key-validation endpoint — a
  1-for-1 match with the 1,310 failed checks in the k6 summary. Separately, payment-service's own
  logs show 20 instances of the fraud-screening call timing out the same way, each one correctly
  handled by the fail-open path (`Letting payment through unscreened`) rather than failing the
  request — so that particular timeout is working exactly as designed. The auth-service timeout is
  not: authentication has no fail-open path, nor should it, so a slow auth-service becomes a
  failed request for the caller.
- This is bottleneck #3 below, caught happening rather than inferred from reading the code — and a
  large share of it is plausibly this specific test environment: twelve JVMs contending for 12
  vCPUs is a much tighter host than any real deployment would run one service on, let alone all of
  them. auth-service is stateless and built to run more than one replica (see the Kubernetes
  manifests); this single-instance, single-host result is the floor its real latency has to clear,
  not necessarily the ceiling this design implies. Worth rerunning against separate hosts before
  drawing a conclusion stronger than "found a real dependency to watch."

## Known bottlenecks

1. **Key validation on every request.** *(Confirmed, this run.)* The gateway calls auth-service
   per request, cached only by auth-service's own usage tracker. At 50/s on a contended host, that
   call's read timeout was hit 1,310 times in two minutes, and every one of those was a failed
   payment request. This is the one to fix first — see Observations above.
2. **The fraud gate inside the payment transaction.** Screening is a synchronous HTTP call made
   while the payment transaction is open, so its latency is added to every write and it holds a
   database connection while it waits. Timeouts are 500ms connect and 1s read, which bounds it —
   but bounded is not free. Confirmed present at 50/s (20 timeouts) but not a source of failed
   requests, because this path fails open.
3. **The outbox relay's poll interval.** 500ms by default. Not exercised as a bottleneck in this
   run — backlog stayed at 0 throughout — but still worth knowing about below whatever write rate
   was actually sustained.
