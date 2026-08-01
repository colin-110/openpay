# ADR-0002: Three operator token tiers instead of one admin token

**Status:** Accepted

## Context

Everything that is not merchant traffic was originally behind a single `X-Admin-Token`. That one
secret opened merchant onboarding, API key issuance, dashboard user creation, webhook secret
rotation, the general ledger, settlement runs, cross-merchant delivery history, and every
service-to-service call.

Those are not the same kind of action, and they are not held by the same kind of caller. The ledger
token wants to live in a finance dashboard. The settlement token wants to live in a cron job. The
service token lives in the environment of every pod on the platform. The onboarding token is used
occasionally, by a person.

A credential embedded in a reporting dashboard or a cron job is far more likely to leak than one a
human uses rarely — and when the shared one leaked, it did not leak read access to the ledger. It
leaked the ability to mint an API key with `payments:write` on any merchant.

## Decision

Three tiers, three secrets, split by **what the action can do** rather than by who is doing it:

| Header | For | Examples |
| --- | --- | --- |
| `X-Admin-Token` | Creating a business identity or a credential that can move money on its own | Onboard a merchant, issue an API key, create a dashboard user, rotate a webhook secret, edit a fraud or routing rule |
| `X-Ops-Token` | Operator reporting and administration that mints nothing | Read the ledger, close a settlement window, cross-merchant delivery history, work the review queue, replay a dead letter, read the audit log |
| `X-Internal-Token` | Service-to-service calls | payment-service reading attempt history, notification-service reading a webhook secret, payment-service calling the fraud gate |

None has a default. An unset token means every path behind it is refused, not open.

The two judgement calls worth stating:

**Editing a fraud or routing rule is admin, not ops**, even though it is the same kind of operator
doing it. Someone who can edit a rule can lower a threshold, let one specific payment through, and
raise it again — leaving nothing in the review queue. Someone who can edit a routing rule can send
all traffic to a base URL of their choosing. Both are closer in authority to issuing a credential
than to reading a report.

**Reading the audit log is ops, not admin**, even though its entries are mostly about admin actions.
Investigating an incident should not require holding the credential that could have caused one.

## Consequences

**Four secrets to manage instead of one.** Compose requires all of them, `run-local.ps1` sets them,
and the Kubernetes secrets template lists them separately with a comment explaining why reusing a
value would quietly undo the whole thing.

**Ten acceptance checks assert the boundaries hold**, in both directions: the admin token is
*refused* on the ledger, on settlement runs, and on cross-merchant delivery history, and the ops
token is accepted on each. Asserting only that the right token works would let a widened tier pass
unnoticed.

**A path covered by two filters needs both tokens.** That is why the routing rules sit on
`/internal/routing-rules` rather than under `/internal/router`, which the service token already
guards — one prefix per tier keeps "operators only" from turning into "operators and services, both
at once".

## Alternatives considered

**One token with scopes.** A token carrying `ledger:read` and `merchants:write` is more flexible and
is what a real platform grows into. Rejected because it needs an issuing and revocation mechanism to
be worth anything, and three static secrets with clearly separated blast radii deliver most of the
benefit for a fraction of the machinery.

**mTLS for service-to-service.** The correct answer for the internal tier, and it removes the shared
secret entirely. Rejected as out of scope: it needs certificate issuance and rotation, which is a
platform concern rather than an application one, and the service mesh that would provide it is not
here.
