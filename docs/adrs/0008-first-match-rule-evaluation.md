# ADR-0008: Rules evaluate first-match, not most-severe

**Status:** Accepted

## Context

A payment can match several fraud rules at once. An eight-lakh payment matches both "review anything
over ₹50,000" and "block anything over ₹5,00,000".

Two ways to resolve it:

- **Most-severe wins.** Evaluate every rule, take the strongest action any of them asks for. Always
  produces the safest outcome, and needs no thought about ordering.
- **First match wins**, in priority order. Evaluation stops at the first rule that fires.

## Decision

First match, in ascending priority order. Ordering is the policy.

## Consequences

**The rule table reads top to bottom.** An operator can look at `GET /internal/fraud/rules` and see
what a payment will do, in order, without simulating anything. That is the property most-severe
gives up: with it, the outcome for any given payment is a function of *all* the rules at once, and
answering "why was this blocked" means evaluating the whole set by hand.

**A `BLOCK` rule can be shadowed by a `REVIEW` rule above it.** This is the real cost, and it is a
genuine footgun — a badly ordered table quietly downgrades a block to a review. It is mitigated
rather than eliminated: the rules endpoint returns rules in evaluation order so the mistake is
visible, the seeded rules put the block rule at priority 10 and the review rule at 20, and the
decision records which rule fired so a wrong outcome is traceable to a specific row.

**There is no `ALLOW` action.** Allowing is what happens when nothing matches, so an allow rule
would exist purely as a way to shadow everything below it — a footgun dressed as a feature.

**The same reasoning applies to routing**, where candidates are tried in priority order and the first
acquirer that accepts wins. A merchant's own rules replace the general ones rather than merging with
them, for a related reason: merging would mean an operator who pinned a merchant to one acquirer
would still fail over to the acquirer they were steering away from.

## Alternatives considered

**Most-severe wins.** Rejected as above: it makes the policy invisible at exactly the moment
somebody needs to read it.

**Both — evaluate all, report all, act on the most severe.** Would give traceability *and* safety.
Rejected because every rule evaluated is a query, and the velocity rules are the expensive ones; on
a gate that sits inside the payment transaction, evaluating rules that cannot change the answer is
latency spent on nothing.
