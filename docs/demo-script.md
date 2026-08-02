# Demo script

Fifteen minutes, showing the things that are actually interesting rather than the things that are
easy to show. A payment succeeding is table stakes; what is worth someone's attention is what
happens when one does not.

## Setup, before anyone is watching

```bash
docker compose -f platform/docker/docker-compose.yml -f platform/docker/docker-compose.apps.yml up -d --build
```

```bash
export OPENPAY_ADMIN_TOKEN=dev-admin-token OPENPAY_OPS_TOKEN=dev-ops-token OPENPAY_INTERNAL_TOKEN=dev-internal-token OPENPAY_JWT_SECRET=dev-jwt-secret-not-for-production-use OPENPAY_DASHBOARD_ORIGINS=http://localhost:5173
```

```bash
bash scripts/seed-demo.sh
```

That leaves a merchant with captured payments, a few refunds, and one payment held for review. Keep
the API key and the dashboard login it prints.

Open two tabs: the dashboard (already running at `:5173` as part of the compose stack) and
Grafana on `:3000`.

---

## 1. A payment, end to end (2 min)

```bash
curl -s -X POST localhost:8080/api/v1/payments -H "X-Api-Key: $KEY" -H "Idempotency-Key: demo-1" -H 'Content-Type: application/json' -d '{"amount":250000,"currency":"INR"}' | jq
```

`201`, status `CREATED`. **Point out that it is not captured** — nothing has touched an acquirer
yet, and the response says so honestly rather than pretending.

Wait three seconds, then read it again: `CAPTURED`. Nothing else was called. In between, the payment
was screened, published, routed to an acquirer, the acquirer called back with a signed callback,
that callback was verified and deduplicated, and the payment moved — all of it asynchronous.

```bash
curl -s localhost:8080/api/v1/payments/$ID/attempts -H "X-Api-Key: $KEY" | jq
```

Which acquirer took it, and its reference.

## 2. Idempotency that means something (1 min)

Same key, same body:

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/api/v1/payments -H "X-Api-Key: $KEY" -H "Idempotency-Key: demo-1" -H 'Content-Type: application/json' -d '{"amount":250000,"currency":"INR"}'
```

`200`, and the original payment. No second charge.

Same key, **different amount**:

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/api/v1/payments -H "X-Api-Key: $KEY" -H "Idempotency-Key: demo-1" -H 'Content-Type: application/json' -d '{"amount":999999,"currency":"INR"}'
```

`409`. That is a client bug, not a retry, and quietly returning the original payment would hide it.

## 3. Risk screening, all three answers (3 min)

**Blocked outright** — over ₹5,00,000:

```bash
curl -s -X POST localhost:8080/api/v1/payments -H "X-Api-Key: $KEY" -H "Idempotency-Key: demo-blocked" -H 'Content-Type: application/json' -d '{"amount":90000000,"currency":"INR"}' | jq
```

`422`. **Nothing was persisted** — a refused payment is not a payment that happened, and a `FAILED`
row would put traffic in the merchant's list they never took.

**Held for review** — over ₹50,000:

```bash
curl -s -X POST localhost:8080/api/v1/payments -H "X-Api-Key: $KEY" -H "Idempotency-Key: demo-held" -H 'Content-Type: application/json' -d '{"amount":9000000,"currency":"INR"}' | jq '{id, status, fraudStatus}'
```

`201`, `fraudStatus: HELD`. Wait and read it again — still `CREATED`. **No acquirer has seen it**,
because routing is driven entirely by an event that was never published. There is no second
mechanism holding it back.

Work the queue as an operator:

```bash
curl -s localhost:8089/internal/fraud/reviews -H "X-Ops-Token: $OPENPAY_OPS_TOKEN" | jq
```

```bash
curl -s -X POST localhost:8089/internal/fraud/reviews/$HELD_ID/resolve -H "X-Ops-Token: $OPENPAY_OPS_TOKEN" -H 'Content-Type: application/json' -d '{"outcome":"ALLOW","resolvedBy":"demo@openpay.test"}' | jq
```

Read the payment again: it routes and captures. The release travelled as an event, which is what
makes an operator's decision survive payment-service being down at the moment they click.

**Rules are data.** Show the table, and that a threshold is one request away rather than a
deployment:

```bash
curl -s localhost:8089/internal/fraud/rules -H "X-Admin-Token: $OPENPAY_ADMIN_TOKEN" | jq '.[] | {name, ruleType, threshold, action, priority}'
```

## 4. An acquirer fails (3 min)

Two acquirers, and failover is the reason for having two. Make one hostile:

```bash
docker exec openpay-mock-bank-a sh -c 'echo' && docker compose -f platform/docker/docker-compose.yml -f platform/docker/docker-compose.apps.yml stop mock-bank-a
```

Create a few payments. They still complete. Watch the router's logs:

```bash
docker logs openpay-provider-router-service --tail 30
```

The first attempt fails, the second acquirer accepts, and after three consecutive failures the
circuit breaker opens and mock-bank-a stops being tried at all.

```bash
curl -s localhost:8085/internal/router/providers -H "X-Internal-Token: $OPENPAY_INTERNAL_TOKEN" | jq
```

Then show the deliberate version — an operator taking an acquirer out of rotation with no
deployment:

```bash
curl -s localhost:8085/internal/routing-rules -H "X-Admin-Token: $OPENPAY_ADMIN_TOKEN" | jq '.[] | {id, providerName, priority, enabled}'
```

```bash
curl -s -X POST localhost:8085/internal/routing-rules/$RULE_ID/disable -H "X-Admin-Token: $OPENPAY_ADMIN_TOKEN" | jq
```

Bring mock-bank-a back and re-enable the rule afterwards.

## 5. The credential tiers (2 min)

The point is that they are enforced in *both* directions.

```bash
curl -s -o /dev/null -w 'admin token on the ledger: %{http_code}\n' "localhost:8086/api/v1/ledger/entries?referenceId=$ID" -H "X-Admin-Token: $OPENPAY_ADMIN_TOKEN"
```

```bash
curl -s -o /dev/null -w 'ops token on the ledger:   %{http_code}\n' "localhost:8086/api/v1/ledger/entries?referenceId=$ID" -H "X-Ops-Token: $OPENPAY_OPS_TOKEN"
```

`401` then `200`. The most privileged token is *refused* — the tiers are not nested, they are
separate, so the credential in a reporting dashboard cannot onboard a merchant.

And a read-only key cannot move money:

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/api/v1/payments -H "X-Api-Key: $READ_ONLY_KEY" -H 'Idempotency-Key: demo-ro' -H 'Content-Type: application/json' -d '{"amount":1000,"currency":"INR"}'
```

`403`. Valid credential, wrong authority.

## 6. The ledger (1 min)

```bash
curl -s "localhost:8086/api/v1/ledger/entries?referenceId=$ID" -H "X-Ops-Token: $OPENPAY_OPS_TOKEN" | jq
```

Both sides of every transaction. Append-only, enforced by the database, and the balance is derived
from the journal rather than stored beside it — a stored balance is a second truth that can
disagree.

## 7. Observability (2 min)

Grafana, **Payment Flow**:

- **Outbox backlog** at zero. Explain what a non-zero one means: everything after creation is
  event-driven, so a stalled relay fails nothing at all — payments are accepted and then silently
  stop advancing. There is no error to alert on, which is why this gauge exists.
- **Screening decisions by rule**, with the block and the review from step 3 visible.
- **Acquirers out of rotation**, still showing the effect of step 4.

Then one request across every service:

```bash
curl -s -D- -o /dev/null localhost:8080/api/v1/payments/$ID -H "X-Api-Key: $KEY" | grep -i correlation
```

Paste the id into Loki: `{container=~"openpay-.+"} |= "<id>"`. Every service's view of that one
request, in order.

## 8. The audit trail (1 min)

```bash
curl -s "localhost:8081/internal/audit?size=20" -H "X-Ops-Token: $OPENPAY_OPS_TOKEN" | jq '.[] | {action, actor, subject, succeeded}'
```

Point out two things: the API key entry records the **prefix**, never the key — an audit log holding
live credentials would be the softest place on the platform to steal one from. And a failed login
appears, which is the harder half: that transaction rolled back, so the entry only exists because
the recorder runs in its own.

Prove it live:

```bash
curl -s -o /dev/null -X POST localhost:8081/api/v1/auth/login -H 'Content-Type: application/json' -d '{"email":"nobody@openpay.test","password":"wrong-password"}'
```

```bash
curl -s "localhost:8081/internal/audit?action=LOGIN_FAILED&size=5" -H "X-Ops-Token: $OPENPAY_OPS_TOKEN" | jq
```

---

## If there is more time

- **`bash scripts/e2e.sh`** — the acceptance suite, live. Around ninety checks, and the ones that
  assert the *closed* holes stay closed are the interesting half.
- **`k6 run tests/performance/provider-outage.js`** — disables an acquirer mid-run and asserts
  acceptance does not move. The claim, tested.
- **Dead-letter replay** — peek, replay, discard, and why discard has to be a separate operation.
