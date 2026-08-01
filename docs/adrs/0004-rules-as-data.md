# ADR-0004: Fraud and routing rules live in tables, not configuration

**Status:** Accepted

## Context

Two sets of rules govern what happens to a payment: which risk thresholds apply, and which acquirer
it goes to. Both started in `application.yml`.

That means changing either one requires a deployment. The two situations where you most want to
change them are:

- a card-testing run in progress at 2am, where the threshold that would catch it is one number in a
  file; and
- an acquirer having a bad afternoon, where the entire reason for integrating two of them is being
  able to move traffic off one.

A threshold you cannot change without a deployment is a threshold nobody changes. Having two
acquirers and needing a release to stop using one is most of the cost of failover with none of the
benefit.

## Decision

`fraud_rules` and `provider_routing_rules` are tables. Both are edited through admin-gated
endpoints, and both are read on every evaluation.

**Rules are disabled, never deleted.** A deleted rule takes with it the only explanation for every
decision that cites it, which is also why `fraud_decisions` stores the rule's *name* rather than a
foreign key.

**The routing table is seeded from configuration on first start only.** The base URLs come from
environment variables that differ between a laptop, Compose, and a cluster, so a SQL seed would bake
one environment's addresses into all of them. After the first start the table is authoritative and
the configuration is inert — re-applying it on every boot would silently undo an operator's decision
to take an acquirer out of rotation, which is the single most likely thing to be sitting in that
table at 3am.

**Neither is cached.** Both are a single indexed query against a table with a handful of rows, next
to an HTTP call to a bank. A cache would buy nothing measurable and would mean an operator who
disabled an acquirer had to wait for a TTL to find out whether it had worked.

## Consequences

**Editing is behind the admin token, not the ops token** — see
[ADR-0002](0002-three-credential-tiers.md). Someone who can edit a fraud rule can lower a threshold,
pass one payment, and raise it again, leaving nothing in the review queue. Someone who can edit a
routing rule can send every payment to a base URL of their choosing.

**A rule read per payment is a query per payment.** Measured against an acquirer round trip it does
not register, but it is a real dependency on the database being up — one that a config-file version
did not have.

**Rule ordering is now an operational concern.** Evaluation is first-match, so a `BLOCK` rule can be
shadowed by a `REVIEW` rule above it. `GET /internal/fraud/rules` returns rules in evaluation order
so that is visible rather than hidden — see [ADR-0008](0008-first-match-rule-evaluation.md).

**Disabling had to be scoped carefully.** Disabling a routing rule stops *new payments* reaching an
acquirer; it does not stop refunds, because a refund goes back to whoever holds the money and
taking an acquirer out of rotation must not strand every refund against what it already took. And
disabling a merchant's only override drops them to the platform defaults rather than taking them
offline.

## Alternatives considered

**A rules engine (Drools, or an expression language).** Arbitrarily expressive. Rejected because
every rule type here has to be answerable from a single indexed query — that constraint is what
keeps the gate fast enough to sit inside payment creation, and an expression language would let
somebody write a rule that is not.

**Configuration with a hot reload.** Keeps the rules in files and watches for changes. Rejected
because it makes the current policy a property of each pod's filesystem: there is no single place to
read what the platform is doing, and two pods can disagree.
