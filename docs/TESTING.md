# Testing OpenPay yourself

Everything here runs against your own machine. Nothing needs a cloud account, a card, or a real
bank — both acquirers are simulated, so no money exists to lose.

## Start the platform

```bash
export OPENPAY_ADMIN_TOKEN=dev-admin-token OPENPAY_OPS_TOKEN=dev-ops-token OPENPAY_INTERNAL_TOKEN=dev-internal-token OPENPAY_JWT_SECRET=dev-jwt-secret-not-for-production-use OPENPAY_DASHBOARD_ORIGINS=http://localhost:5173
```

```bash
docker compose -f platform/docker/docker-compose.yml -f platform/docker/docker-compose.apps.yml up -d --build
```

First build takes 10–20 minutes. After that, starting is under a minute.

```bash
docker compose -f platform/docker/docker-compose.yml -f platform/docker/docker-compose.apps.yml ps
```

Wait until every service says `healthy`. If a build runs out of memory, build one service first so
the shared Maven layer is cached, then the rest reuse it:

```bash
docker compose -f platform/docker/docker-compose.yml -f platform/docker/docker-compose.apps.yml build gateway-service
```

## The one command that proves it works

```bash
./scripts/e2e.sh
```

96 checks over real HTTP against the running stack: idempotency, authentication, authority,
routing, refunds, the ledger, the audit trail, and the full asynchronous capture flow. `PASS=96
FAIL=0` means the platform is genuinely working, not merely started.

This is the one to run after any change.

## Take a payment the easy way

If you only want to see the thing work, there is a shop.

```bash
OPENPAY_ADMIN_TOKEN=dev-admin-token ./scripts/seed-demo.sh
```

Put the API key it prints into `platform/docker/.env` as `STOREFRONT_API_KEY=...`, then:

```bash
docker compose -f platform/docker/docker-compose.yml -f platform/docker/docker-compose.apps.yml up -d demo-storefront
```

Open http://localhost:8090 and buy the kettle. The payment is accepted immediately, and then you
watch it get sent to an acquiring bank, authorised and captured on its own — about a second and a
half, with nothing clicked after Pay. Then follow the link into the dashboard and it is there.

That is the whole platform in one screen: the merchant is never waiting for the bank, and
everything after the response happens over Kafka.

## Take a payment by hand

Onboard a merchant and issue it a key:

```bash
curl -s -X POST http://localhost:8082/api/v1/merchants -H 'Content-Type: application/json' -H 'X-Admin-Token: dev-admin-token' -d '{"merchantCode":"my-shop","legalName":"My Shop","webhookUrl":null,"defaultCurrency":"INR"}'
```

Take the `id` from that response and issue a key (the plaintext key is shown exactly once):

```bash
curl -s -X POST http://localhost:8081/api/v1/api-keys -H 'Content-Type: application/json' -H 'X-Admin-Token: dev-admin-token' -d '{"merchantId":"PASTE_MERCHANT_ID","name":"my-key","scope":"payments:write","expiresAt":null}'
```

Create a payment through the gateway:

```bash
curl -s -X POST http://localhost:8080/api/v1/payments -H 'Content-Type: application/json' -H 'X-Api-Key: PASTE_API_KEY' -H 'Idempotency-Key: my-first-payment' -d '{"amount":25000,"currency":"INR"}'
```

It comes back `CREATED`. Wait a few seconds and read it again — it will be `CAPTURED`, with no
further action from you. That is the outbox, Kafka, the router, the simulated acquirer and its
signed callback all doing their jobs:

```bash
curl -s http://localhost:8080/api/v1/payments/PASTE_PAYMENT_ID -H 'X-Api-Key: PASTE_API_KEY'
```

## Things worth trying to break

**Send the same idempotency key twice.** Re-run the create command exactly as-is. You get the same
payment id back, and only one payment exists. Now change the amount but keep the key — that is
refused, because a key that means one thing must not quietly come to mean another.

**Send a payment large enough to be held.** Anything over 50,000 rupees (`"amount":6000000`) is
held for review rather than routed. Over 500,000 (`"amount":60000000`) is refused outright with
422 and nothing is stored.

**Send eleven identical amounts inside five minutes.** The eleventh is blocked — the repeated-amount
rule. This is also the trap that made an early load test look broken.

**Send more than 30 writes in 5 seconds.** The gateway rate-limits you with 429.

**Use a read-only key.** Issue one with `"scope":"payments:read"` and try to create a payment. It
reads fine and cannot move money.

**Take an acquirer offline mid-flight** and watch payments keep succeeding:

```bash
docker pause openpay-mock-bank-a
```

Payments still complete — the router fails over to `mock-bank-b`. `docker unpause openpay-mock-bank-a`
when done.

## See it happening

| | |
| --- | --- |
| Dashboard | http://localhost:5173 — sign in, watch payments settle, issue a refund |
| Grafana | http://localhost:3000 — payment flow, outbox backlog, acquirer health |
| Prometheus | http://localhost:9090 |
| Mailpit | http://localhost:8025 — every email the platform sends |

For a dashboard login, create a user:

```bash
curl -s -X POST http://localhost:8081/api/v1/users -H 'Content-Type: application/json' -H 'X-Admin-Token: dev-admin-token' -d '{"merchantId":"PASTE_MERCHANT_ID","email":"me@example.com","password":"correct-horse-battery-staple","role":"MERCHANT_ADMIN"}'
```

## Break a dependency on purpose

```bash
OPENPAY_ADMIN_TOKEN=dev-admin-token ./scripts/fault-injection.sh
```

Pauses Redis, fraud-service, auth-service and Kafka in turn — real containers, frozen rather than
stopped — and asserts that the documented behaviour in
[ARCHITECTURE.md § 5](ARCHITECTURE.md#5-failure-modes) actually holds, then unpauses and checks
recovery. 13 checks. It always unpauses what it paused, but nothing can trap `SIGKILL`, so if you
force-kill it, run `docker ps` and unpause anything still `(Paused)`.

## Load test it

Needs [k6](https://k6.io), or use the Docker image as shown.

Raise the per-merchant write limit first, or you will be measuring the rate limiter rather than the
platform:

```bash
echo 'RATE_LIMIT_PER_WINDOW=200000' >> platform/docker/.env
docker compose -f platform/docker/docker-compose.yml -f platform/docker/docker-compose.apps.yml up -d gateway-service
```

```bash
docker run --rm -i --network host -v "$PWD/tests:/tests" -e OPENPAY_ADMIN_TOKEN=dev-admin-token grafana/k6 run /tests/performance/stress.js
```

Four rate tiers with p50/p95/p99 and achieved throughput per tier. Other scenarios in
`tests/performance/`: `payment-create.js` (steady load), `webhook-spike.js` (a 40× callback spike),
`provider-outage.js` (an acquirer removed mid-run), `soak.js` (drift over time).

**Put the rate limit back afterwards** — remove that line from `.env` and restart the gateway.

Recorded results, with the hardware they came from, are in
[tests/performance/baseline.md](../tests/performance/baseline.md). Compare against your own machine
rather than trusting the numbers.

## Run the automated tests

```bash
./mvnw test
```

373 backend tests, no infrastructure needed.

```bash
./mvnw verify
```

Also runs the integration tests, which start real PostgreSQL, Kafka and Mailpit containers through
Testcontainers. Needs Docker running and a few gigabytes free — it will fail to pull images if the
platform is already using all of it.

```bash
cd web/dashboard && npm install && npm test
```

47 frontend tests.

## When something looks wrong

```bash
docker compose -f platform/docker/docker-compose.yml -f platform/docker/docker-compose.apps.yml logs -f payment-service
```

Every log line carries the correlation id of the request that caused it, so one id follows a
payment across all eleven services. Grafana's Loki panel searches them together.

```bash
docker exec openpay-postgres psql -U openpay -d openpay_payment -c "SELECT id, status, fraud_status FROM payments ORDER BY created_at DESC LIMIT 5;"
```

If a payment is stuck in `CREATED`, check whether its event was relayed:

```bash
docker exec openpay-postgres psql -U openpay -d openpay_payment -c "SELECT count(*) FROM outbox_events WHERE published_at IS NULL;"
```

A number that keeps climbing means the relay is not draining — usually Kafka. Zero means the event
was published and the problem is downstream.

## Start over

```bash
docker compose -f platform/docker/docker-compose.yml -f platform/docker/docker-compose.apps.yml down -v
```

`-v` deletes the volumes, so every merchant, payment and ledger entry is gone. That is the fastest
way back to a clean platform, and there is nothing in it worth keeping.
