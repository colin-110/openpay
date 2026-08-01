# Operations runbook

What to do when something is wrong, written for whoever is holding the pager rather than for whoever
wrote the code.

Every symptom below has been arranged the same way: what you would see, what it actually means, and
what to do about it. Where a failure is silent — and several of the important ones here are — that
is called out first.

## Where to look

| | |
| --- | --- |
| Dashboards | Grafana on `:3000` — **Payment Flow** first, then **Service Health** |
| Logs | Loki, `{container=~"openpay-.+", level=~"ERROR\|WARN"}` |
| One request, everywhere | `{container=~"openpay-.+"} \|= "<correlation-id>"` |
| Health | `GET /actuator/health` on any service |

Every response carries `X-Correlation-Id`. A merchant reporting a problem who can quote it turns an
investigation into one query.

---

## Payments are accepted but nothing completes

**The most important symptom in this document, because nothing errors.** Payments return `201`, the
API is healthy, latency is fine, and the merchant's payments sit in `CREATED` forever.

**Look at:** the outbox backlog — `openpay_outbox_unpublished`, the first panel on Payment Flow.

If it is climbing and not coming back down, the relay is not publishing.

**Check, in order:**

1. **Is Kafka reachable?** `openpay_outbox_unpublished` climbing across *every* service at once is
   almost always the broker.
   ```bash
   kubectl -n openpay logs deploy/payment-service --tail=100 | grep -i kafka
   ```
2. **Is the relay running?** It is disabled by `openpay.outbox.relay-enabled=false`, which is a test
   setting that has no business being set in a deployment.
3. **Are rows failing repeatedly?** `outbox_events.attempts` and `last_error` say why.
   ```sql
   SELECT topic, attempts, last_error, count(*) FROM outbox_events
   WHERE published_at IS NULL GROUP BY 1,2,3 ORDER BY 4 DESC;
   ```

**Recovery:** the relay drains on its own once the cause is fixed — nothing is lost, because the
rows are committed. Watch the backlog come down rather than intervening.

---

## A payment is stuck in `CREATED`

Distinguish two very different cases first:

```bash
curl -s "$GATEWAY/api/v1/payments/$ID" -H "X-Api-Key: $KEY" | jq '{status, fraudStatus}'
```

**`fraudStatus: HELD`** — it is not stuck, it is waiting for a human. Nothing was published, so no
acquirer has seen it. Work the queue:

```bash
curl -s "$FRAUD/internal/fraud/reviews" -H "X-Ops-Token: $OPS_TOKEN"
```

```bash
curl -X POST "$FRAUD/internal/fraud/reviews/$ID/resolve" -H "X-Ops-Token: $OPS_TOKEN" -H 'Content-Type: application/json' -d '{"outcome":"ALLOW","resolvedBy":"you@example.com"}'
```

Release is asynchronous — fraud-service publishes, payment-service consumes — so give it a second.

**`fraudStatus: ALLOWED`** — the event was published and routing has not happened. Check the outbox
backlog above, then the router's dead letters:

```bash
curl -s "$ROUTER/internal/dlq?topic=payment.created.v1&limit=20" -H "X-Ops-Token: $OPS_TOKEN"
```

---

## Payments are failing at the acquirer

**Look at:** "Acquirers out of rotation" and "Payments that reached no acquirer" on Payment Flow.

The `reason` tag on `openpay_routing_exhausted_total` tells you which team this belongs to:

| Reason | Meaning | Who |
| --- | --- | --- |
| `all_providers_exhausted` | Every acquirer refused or timed out | The acquirers, or the network to them |
| `no_matching_rule` | No routing rule matched this payment at all | Whoever last edited the routing table |

For `no_matching_rule`, ask what *would* have been tried:

```bash
curl -s "$ROUTER/internal/routing-rules/resolve?merchantId=$MERCHANT&currency=INR&amount=10000" -H "X-Admin-Token: $ADMIN_TOKEN"
```

An empty array is the bug. The usual cause is a merchant-specific rule: a merchant with any enabled
rule of its own does not fall back to the general ones, so a rule with a currency or amount band
that does not match leaves them with nothing.

**To take a bad acquirer out of rotation** (this is what the routing table is for — it needs no
deployment):

```bash
curl -X POST "$ROUTER/internal/routing-rules/$RULE_ID/disable" -H "X-Admin-Token: $ADMIN_TOKEN"
```

Refunds against that acquirer keep working. Disabling stops new payments; it does not strand the
money it already holds.

---

## Screening is unavailable

**Look at:** the `screening` tag on `openpay_payments_accepted_total`. `UNSCREENED` means
fraud-service could not be reached and the payment went through anyway.

This is the designed behaviour — see [ADR-0003](adrs/0003-fraud-gate-fails-open.md) — and it is
*not* an error anywhere. Nothing pages on it, so this metric is the only signal.

**Do:**

1. Get fraud-service healthy. Everything accepted meanwhile is unscreened.
2. When it is back, find what went through the window and review it by hand:
   ```sql
   SELECT id, merchant_id, amount, created_at FROM payments
   WHERE fraud_status = 'UNSCREENED' AND created_at > now() - interval '1 hour';
   ```

**Do not** set `FRAUD_FAIL_OPEN=false` to "fix" this during an incident. That converts a risk window
into a full outage: every merchant on the platform stops taking money.

---

## The review queue is growing

**Look at:** `openpay_fraud_open_reviews`.

Every entry is a merchant's customer waiting at a checkout, and the queue is worked oldest-first for
that reason.

A sudden jump is usually one rule, not a change in traffic:

```bash
curl -s "$FRAUD/internal/fraud/rules" -H "X-Admin-Token: $ADMIN_TOKEN"
```

Compare against `openpay_fraud_decisions_total` by `rule`. If one rule is responsible and it is
wrong, disable it — the rule table exists precisely so this does not need a deployment:

```bash
curl -X POST "$FRAUD/internal/fraud/rules/$RULE_ID/disable" -H "X-Admin-Token: $ADMIN_TOKEN"
```

Disabling stops it matching new payments. It does not release payments already held; those still
need resolving.

---

## Messages in a dead-letter topic

Every consuming service exposes `/internal/dlq` on the ops token. **Look before acting** — peeking
commits nothing:

```bash
curl -s "$PAYMENT/internal/dlq/topics" -H "X-Ops-Token: $OPS_TOKEN"
```

```bash
curl -s "$PAYMENT/internal/dlq?topic=provider.callback-received.v1&limit=20" -H "X-Ops-Token: $OPS_TOKEN"
```

Each record carries the exception type and message that put it there.

**If the cause is fixed**, replay:

```bash
curl -X POST "$PAYMENT/internal/dlq/replay?topic=provider.callback-received.v1&limit=20" -H "X-Ops-Token: $OPS_TOKEN"
```

**If it will never succeed** — a malformed payload from a bad deployment, say — discard explicitly:

```bash
curl -X POST "$PAYMENT/internal/dlq/discard?topic=provider.callback-received.v1&limit=20" -H "X-Ops-Token: $OPS_TOKEN"
```

Do not use replay to clear a queue. A message whose cause has not been fixed goes straight back to
the DLQ at a new offset, and you have moved the problem by one.

---

## Latency is up across every service

**Look at:** "Database connections in use" against the pool maximum on Service Health.

A pool pinned at its limit is the usual cause, and it looks like a slow application rather than a
ceiling. Two things in this codebase hold a connection while waiting on the network:

- **the fraud gate**, which is called inside the payment transaction (bounded at 1s);
- **key validation**, which the gateway does per request.

If pools are fine, check `hikaricp_connections_pending` and then whether the database itself is
slow.

---

## Nobody can sign in

Check whether it is the throttle before anything else:

```bash
curl -s "$AUTH/internal/audit?action=LOGIN_THROTTLED&size=50" -H "X-Ops-Token: $OPS_TOKEN"
```

`LOGIN_THROTTLED` is recorded separately from `LOGIN_FAILED` because a throttled attempt never
reached the password check. Two budgets apply: per-account (tight) and per-source (loose). A whole
office behind one NAT gateway can trip the source budget.

If Redis is down the limiter fails open, so throttling degrades but sign-in still works. Redis is
deliberately excluded from auth-service's health aggregate for that reason — reporting the service
DOWN would be a false alarm.

---

## A merchant is not receiving webhooks

```bash
curl -s "$NOTIFICATION/internal/webhooks/deliveries?merchantId=$MERCHANT" -H "X-Ops-Token: $OPS_TOKEN"
```

`status` and `response_status` say whether it was never attempted, refused, or delivered.

Two causes worth checking before anything else:

- **The URL was refused by policy.** The platform will not POST to a private range or to cloud
  metadata addresses, and the check is applied again at connect time — so a hostname that resolves
  to a private address is refused then, not at onboarding.
- **The signature does not verify at their end.** If they rotated expectations without rotating with
  us, rotate the secret and give them the new one. It is returned exactly once:
  ```bash
  curl -X POST "$MERCHANT_SVC/api/v1/merchants/$MERCHANT/webhook-secret" -H "X-Admin-Token: $ADMIN_TOKEN"
  ```

---

## Someone may have used a credential they should not have

The audit log is on the ops tier, so investigating does not require holding the credential that
could have caused the incident.

```bash
curl -s "$AUTH/internal/audit?merchantId=$MERCHANT&size=200" -H "X-Ops-Token: $OPS_TOKEN"
```

```bash
curl -s "$MERCHANT_SVC/internal/audit?action=MERCHANT_CREATED" -H "X-Ops-Token: $OPS_TOKEN"
```

`API_KEY_ISSUED` records the key **prefix**, never the key. To trace what a prefix has been doing,
join it against `api_keys.last_used_at` and the gateway logs.

**If a token tier is compromised**, rotate that one — not all of them. The tiers exist so the blast
radius is one tier; rotating everything turns a contained incident into a platform-wide restart.

---

## Rolling back

Images are tagged with the commit SHA, never `latest`, so a rollback is exact:

```bash
kubectl -n openpay set image deployment/payment-service payment-service=ghcr.io/OWNER/payment-service:$PREVIOUS_SHA
```

**Flyway migrations do not roll back.** Check whether the version you are rolling back to can read
the current schema. Everything so far has been additive — new tables, nullable columns, columns with
defaults — so an older jar tolerates a newer schema. That is a property to keep deliberately, not
one to assume.
