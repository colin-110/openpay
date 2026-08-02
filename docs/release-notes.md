# Release notes

## 0.3.0 — sessions that renew themselves, and a clean bill on dependencies

### Refresh tokens, with theft detection

`POST /api/v1/auth/login` now returns a refresh token alongside the access token. The access token
TTL dropped from an hour to 15 minutes — safe to shorten only because the dashboard now renews it
silently in the background (`POST /api/v1/auth/refresh`), 60 seconds before it would otherwise
expire, instead of dropping the user back to a login screen.

Refresh tokens rotate on every use and are single-use: presenting one that was already rotated away
is treated as a stolen credential, not a stale one, and revokes every other session the user has —
not just the token replayed. `POST /api/v1/auth/logout` revokes one session by name and is
idempotent. Full reasoning, including the transaction-boundary bug this surfaced in testing, is in
[SECURITY-AUDIT.md § Refresh tokens](SECURITY-AUDIT.md#refresh-tokens--a-revocable-session-behind-a-stateless-access-token).

**For integrations:** `LoginResponse` gains `refreshToken` and `refreshExpiresAt`. The access token
shape and every existing verification path are unchanged.

New table: `refresh_tokens` (auth-service, V5) — token hash, issue/expiry timestamps, and a
self-referencing `replaced_by` that is how a rotated-away token is told apart from one that simply
expired.

### TLS at the edge

Caddy now fronts the compose stack the same way the Kubernetes Ingress already did, with `tls
internal` minting a local CA automatically — no certificates to generate or commit. Internal
service-to-service traffic is still plain HTTP; that boundary and the reasoning behind it are in
[SECURITY-AUDIT.md, finding 12](SECURITY-AUDIT.md#12-no-tls-anywhere--info).

### Dependency CVEs

`docker scout` found 30 known vulnerabilities (5 critical) across Tomcat, Jackson, Kafka, and Spring
itself, all coming from Spring Boot 3.5.4's managed versions. Fixed by bumping to 3.5.16 — confirmed
against the BOM directly rather than assumed — with one CVE accepted because it is baked into the
base JRE image's build tooling, not the running application. Full table in
[SECURITY-AUDIT.md, finding 13](SECURITY-AUDIT.md#13-outdated-dependencies-carrying-known-cves--critical-fixed-1-accepted).

### The dashboard is finally a container

`web/dashboard` had routes, a build, and no `Dockerfile` — every reference to it in compose and the
Kubernetes manifests assumed a container that did not exist. It has one now (two-stage, `node:22`
building into `nginx:1.27-alpine`), wired into both.

### Still not built

- A payout rail. Settlement batches what a merchant is owed and clears the payable in the ledger,
  and then nothing sends money anywhere.
- Email notification. Delivery is HTTP webhooks only.
- Real acquirers. Both are simulated, so nothing ever leaves a database.

## 0.2.0 — the remaining phases

Closes every gap the roadmap listed as open, and finishes phases 9 and 11 through 17.

### Risk screening is now in the payment path

`fraud-service` screens every payment before it is written. Rules live in a table rather than in
configuration, because the moment you most want to change a threshold is at 2am during a card-testing
run, and a threshold that needs a deployment is a threshold nobody changes.

Three answers, and each is handled differently:

| Answer | The merchant sees | The platform does |
| --- | --- | --- |
| `ALLOW` | `201` | Publishes `payment.created.v1`; routing proceeds |
| `REVIEW` | `201`, `fraudStatus: HELD` | Persists the payment and publishes **nothing** |
| `BLOCK` | `422 payment_blocked` | Persists nothing at all |

A held payment reaches no acquirer because routing is driven entirely by an event that was never
published — there is no second mechanism that has to agree. Closing the review publishes
`fraud.check-completed.v1`, and payment-service releases it from there, so an operator's decision
survives payment-service being down at the moment they click.

**For integrations:** `PaymentResponse` gains a `fraudStatus` field. Existing payments are backfilled
to `ALLOWED`. A `422` with code `payment_blocked` is new and is not retryable — the same request will
be refused again.

Screening **fails open** when fraud-service is unreachable, and records the payment as `UNSCREENED`
rather than `ALLOWED`. Failing closed would let one unhealthy risk service stop every merchant on the
platform from taking money. `FRAUD_FAIL_OPEN=false` reverses it.

### An audit trail that survives the transaction it records

`audit_logs` in auth-service and merchant-service: logins, throttled attempts, key issuance, user
creation, onboarding, and secret rotation.

The recorder runs in its own transaction, which is the whole design. A refused login rolls back, and
without a separate transaction the record of the attempt would roll back with it — leaving a log
containing only the sign-ins that worked.

Nothing recorded is usable: key issuance stores the prefix, never the key.

Read it at `GET /internal/audit` on the **ops** tier — investigating an incident should not require
holding the credential that could have caused one. There is no write endpoint.

### Dead letters are something you can act on

Every consuming service exposes `/internal/dlq`: peek without committing, replay to the original
topic, or discard explicitly.

Discard is separate from replay on purpose. A message whose cause has not been fixed goes straight
back to the DLQ at a new offset, so using replay to clear a queue only moves the poison along by
one.

### Routing rules moved into a table

`provider_routing_rules`, seeded once from configuration and authoritative afterwards. Taking a
misbehaving acquirer out of rotation is now a request rather than a deployment.

Rules narrow by merchant, currency, and amount band. A merchant's own rules **replace** the general
ones rather than merging with them — pinning a merchant to one acquirer usually means *and not the
other one*, and a merged list would fail over to exactly the acquirer being steered away from.

Disabling a rule stops new payments to that acquirer and does **not** stop refunds against it.

### Observability that was running but empty

Grafana and Loki were up with no datasources, no dashboards, and nothing shipping logs. All of it is
provisioned from files now: two dashboards, Promtail shipping container logs, and business metrics
that count the payment lifecycle rather than HTTP requests.

Two metric naming bugs were found by scraping the endpoint rather than by reading documentation, and
both are now covered by a test that fails the build:

- Micrometer appends `_total` itself, so writing the Prometheus name gives `_total_total`.
- `_created` is a reserved OpenMetrics suffix and is stripped — `openpay.payments.created` arrived as
  `openpay_payments_total`, having silently lost the word that said what it counted.

### Kubernetes, load tests, and CI

- `platform/k8s/` — manifests with probes split three ways, autoscaling only where it helps,
  default-deny network policy, and an ingress that publishes three hosts and hides every operator
  surface. The README ends with what would have to change for it to be real.
- `tests/performance/` — three k6 scenarios, including one that disables an acquirer mid-run and
  asserts acceptance does not move.
- CI now gates the dashboard build, the Kubernetes manifests, the shell scripts, and the secrets
  template, and builds container images tagged with the commit SHA. A separate nightly workflow
  starts the whole platform and runs the acceptance suite against it.

### Documentation

`docs/diagrams/` (eight diagrams, Mermaid so they can be edited in the same commit as the change),
`docs/adrs/` (ten decision records, each naming the alternative and why it lost), plus a
[runbook](runbook.md), a [release checklist](release-checklist.md), and a
[demo script](demo-script.md).

### Schema

Additive except for three index drops, so the previous jar can still read the new schema.

| Service | Change |
| --- | --- |
| payment-service | `payments.fraud_status` (V8), index tuning (V9) |
| provider-router-service | `provider_routing_rules` (V2) |
| fraud-service | `fraud_rules`, `fraud_decisions`, `outbox_events` (new database) |
| auth-service | `audit_logs` (V4) |
| merchant-service | `audit_logs` (V4) |

The V9 index changes replace two indexes that did not match the queries that run — `idx_refunds_status`
led with `status`, so no merchant-scoped query could use it — and drop one that was a strict prefix
of a wider index and one on a column nothing queries.

### Still not built

- Refresh tokens. A session expires and you sign in again.
- A payout rail. Settlement batches what a merchant is owed and clears the payable in the ledger,
  and then nothing sends money anywhere.
- Email notification. Delivery is HTTP webhooks only.
- Real acquirers. Both are simulated, so nothing ever leaves a database.
