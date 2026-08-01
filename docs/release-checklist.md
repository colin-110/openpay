# Release checklist

Short on purpose. A checklist long enough to skim is a checklist that gets skimmed.

## Before

- [ ] `./mvnw clean verify` is green locally, including the `*IT` suites. CI runs it on JDK 21 and
      25; a difference between them is the thing that check exists to catch.
- [ ] The acceptance suite passes against a running stack: `bash scripts/e2e.sh`.
- [ ] Migrations are **additive**. New tables, nullable columns, or columns with a default. Anything
      that drops or narrows a column means the previous jar cannot read the new schema, and rollback
      stops being available at the moment it is most needed.
- [ ] New configuration has a default that fails closed. Every token and the JWT secret are empty by
      default and refuse the paths behind them; a new one that defaults to permissive is a security
      change disguised as a convenience.
- [ ] New metrics appear in a scrape, not just in the code. `MetricsExposureIT` covers the payment
      ones; a new counter that ends in `created`, or that carries a `_total` suffix, will not be
      named what you expect — see [ADR notes on naming](../README.md#metric-naming-the-hard-way).
- [ ] Any dashboard panel querying a renamed metric was renamed with it.
- [ ] `docs/roadmap.md` and the README's Status section reflect what is now true.

## Deploying

- [ ] Images are tagged with the commit SHA. Never `latest` — a tag that moves makes a rollback a
      guess about what was running.
- [ ] The four token secrets are set, and are four **different** values. Reusing one quietly undoes
      the entire point of the tiers.
- [ ] `OPENPAY_JWT_SECRET` is at least 32 bytes. auth-service refuses to start otherwise, which is
      the intended behaviour and still a confusing five minutes if it happens in a deploy.
- [ ] `OPENPAY_DASHBOARD_ORIGINS` names the real origin. Empty means every cross-origin request is
      refused, which presents as "the dashboard is broken".
- [ ] Roll one service at a time and watch it, rather than everything at once.

## After

- [ ] Every rollout reached `Available`: `kubectl -n openpay get deploy`.
- [ ] **Outbox backlog is at or near zero.** This is the check that catches a broken deployment
      which reports perfectly healthy: payments are accepted, committed, and quietly stop advancing.
- [ ] A real payment completes end to end. Create one, wait, confirm it reaches `CAPTURED`.
- [ ] Error rate on Service Health has not moved.
- [ ] No unexpected growth in the review queue or in dead letters.

## Rolling back

```bash
kubectl -n openpay set image deployment/payment-service payment-service=ghcr.io/OWNER/payment-service:$PREVIOUS_SHA
```

Migrations do not roll back with the image. If the release included a schema change, confirm the
previous jar can still read the current schema before rolling back — which is exactly why the
additive rule is the first item in this document.

## Cutting the release

- [ ] Tag it: `git tag -a v0.2.0 -m "..." && git push origin v0.2.0`
- [ ] Update [release-notes.md](release-notes.md) with what changed and what it means for anyone
      integrating.
