# Showing OpenPay to someone

A ten-minute walkthrough that starts with them watching a payment happen and ends with them
watching an acquirer die without losing one. Everything here is live — nothing is a recording or a
mock-up.

## Fifteen minutes before

```bash
docker compose -f platform/docker/docker-compose.yml -f platform/docker/docker-compose.apps.yml --profile shop up -d
```

That is everything: `demo-provisioner` onboards the demo merchant, mints the shop's keys and a
dashboard login, and exits before the shop starts. The shop prints the dashboard credentials on its
own front page, so there is nothing to look up mid-demo and no password to type on screen.

Optionally seed some history, so the dashboard is not empty before anyone has bought anything:

```bash
OPENPAY_ADMIN_TOKEN=dev-admin-token ./scripts/seed-demo.sh
```

That creates a *second* merchant with its own login, which is worth knowing: it is not the one the
shop pays as. For the walkthrough below, sign in with the credentials the shop shows you.

Then, and this matters more than it sounds: **take one payment and throw it away.** The JVMs are at
their slowest on the first request after a start, and a demo whose first action takes four seconds
starts you apologising.

```bash
OPENPAY_ADMIN_TOKEN=dev-admin-token ./scripts/demo-payment.sh
```

Have open, in this order: http://localhost:8090, http://localhost:5173, a terminal,
http://localhost:3000. Sign into the dashboard **now** — the credentials the seeder printed — so
you are not typing a password on screen.

## 1. The problem (45 seconds, no screen)

> Taking a payment is easy. Taking one correctly is not, and almost none of the difficulty is in the
> happy path. A customer taps Pay twice. The bank goes down mid-transaction. The service crashes
> between saving the payment and telling anyone about it. The books stop balancing and nobody can
> say which payment is wrong.
>
> I built the gateway that handles those, and then I tried to break it.

Do not open the README. Nobody wants to watch someone read.

## 2. Buy something (45 seconds — open with this, not with a terminal)

Go to http://localhost:8090. It is a shop. Buy the kettle.

Say almost nothing while it runs, because the screen is doing the talking: the payment is accepted
immediately, and then four steps tick themselves off — sent to the acquiring bank, authorised,
captured — with the elapsed time against each. Typical is about a second and a half.

> Nothing was clicked after Pay. The shop's server got an id back straight away and the rest
> happened on its own: an event went onto Kafka, a router picked an acquirer, the bank called back
> twice with signed callbacks, and each one was verified before it was believed.

The one line worth saying out loud, because it is the whole architecture in a sentence:

> The merchant was never waiting for the bank.

Then click through to the dashboard from the shop — the payment is already there.

**Why start here.** Everything else in this walkthrough is a terminal, and a terminal only
convinces someone who already believes you. This is the ninety seconds where a person who has never
seen a payment gateway understands what you built.

## 3. The same payment, in detail (90 seconds)

```bash
OPENPAY_ADMIN_TOKEN=dev-admin-token ./scripts/demo-payment.sh
```

Now the terminal, for the parts a checkout page cannot show. While it runs, narrate what the output
is telling them:

- **`status CREATED`, and the command has already returned.** The merchant is not waiting for the
  bank. Everything after this point happens on its own.
- **`cu***@okhdfcbank`** — the payment method comes back masked. The platform stores a network and
  the last four and nothing worth stealing.
- **`t+1s PENDING_PROVIDER`, `t+2s CAPTURED`** — that is the router choosing an acquirer, the bank
  accepting, and two HMAC-signed callbacks coming back and being verified. No client involvement.
- **The ledger block at the end.** `DEBIT 249900 / CREDIT 249900 / net 0`. Say the important part:
  *a database trigger refuses any transaction that does not sum to zero, so the books cannot go
  wrong through an application bug.*

## 4. The same payment, as the merchant sees it (60 seconds)

Switch to the dashboard. Payments list, click the newest one.

Worth pointing at:

- The **timeline** on the payment: created → authorised → captured, with timestamps seconds apart.
- One payment in the list is **held for review** — the seeder makes sure of it. That payment
  reached no acquirer at all. Screening decided before anything was routed.
- **Refund** one of the captured payments while they watch. It goes out to the acquirer and comes
  back the same asynchronous way.

## 5. Kill a bank (2 minutes — this is the one they remember)

```bash
docker pause openpay-mock-bank-a
```

> That's the acquirer taking most of the traffic. It is not stopped, it is frozen — sockets open,
> answering nothing. That is what a real outage looks like, and it is worse than a clean crash.

```bash
OPENPAY_ADMIN_TOKEN=dev-admin-token ./scripts/demo-payment.sh
```

It still captures. The attempts table shows attempt 1 against `mock-bank-a` and attempt 2 against
`mock-bank-b`.

> Losing an acquirer costs latency, not payments. I did not want to take my own word for it, so it
> is a load test: twenty-four hundred payments with an acquirer pulled out of rotation mid-run,
> **100% acceptance**.

```bash
docker unpause openpay-mock-bank-a
```

If they seem interested, this is the moment for:

```bash
OPENPAY_ADMIN_TOKEN=dev-admin-token ./scripts/fault-injection.sh
```

Thirteen checks that pause Redis, fraud-service, auth-service and Kafka in turn and assert the
documented failure behaviour still holds. Mention what it found the first time it ran: the Redis
client had no command timeout, so "the rate limiter fails open" actually meant "hangs for sixty
seconds, then fails open."

## 6. Watching it (45 seconds)

Grafana, the Payment Flow dashboard. Two panels are worth naming:

- **Outbox backlog.** If this climbs, events are not being relayed — payments are being accepted
  and none of them are advancing. Every health check in the system would still say healthy.
- **Acquirers out of rotation.** It was 1 during the step above.

## 7. What it costs and what it survives (60 seconds)

```
50 payments/second sustained   p50 91ms   p95 253ms   0% errors, 0 dropped
```

Say where it was measured — one laptop running all twenty-two containers — before they ask. Then
the honest part, which lands better than the numbers do:

> That is a floor, not a ceiling. Above fifty a second this host stops being able to measure
> itself — I have runs where 150/s passed cleanly and 100/s failed, which is the load generator and
> the system under test fighting over the same twelve cores. A real number needs hardware that is
> not also running the thing being tested, so I am not going to claim one.

If they push on it, the better story is *why* the earlier numbers were wrong:

> The load tests used to drive a single merchant, which trips a velocity rule about five seconds
> in — so most of what they were counting as payments were being held for review, never routed to
> an acquirer and never posted to the ledger. Roughly a tenth of the work, counted as a payment. The
> old figure was three times higher and measuring almost nothing.

## 8. Finish on a bug (30 seconds)

Best closing line available, because it shows judgement rather than output:

> The load tests found three bugs in themselves before they found any in the platform. One
> threshold was written against a tag that does not exist on custom metrics, so it matched zero
> samples and passed on every run ever recorded. When I fixed it, it immediately failed a run it had
> been calling green.
>
> A test that cannot fail is worse than no test, because it reads like coverage.

## If they ask to see the code

Three files, in this order. None of them is a feature.

1. [`docs/adrs/`](adrs/) — ten decisions, each naming the alternative it rejected.
2. [`tests/performance/baseline.md`](../tests/performance/baseline.md) — a hypothesis recorded,
   tested, found wrong, and left in the document with the correction rather than quietly rewritten.
3. [`PaymentService.createPayment`](../services/payment-service/src/main/java/com/openpay/payment/application/PaymentService.java)
   — the comment explaining why the transaction is programmatic. A `@Transactional` method called
   from inside the same class never reaches the Spring proxy, so the payment would have committed
   without its outbox row.

## When you cannot run it live

A remote first-round, or a recruiter who will not install Docker.

**Record it.** Six to eight minutes, the sections above, no editing beyond trimming the ends. Put
the link at the top of the README. A recording that always works beats a live demo that might not.

**Do not deploy it just to have a URL.** It is eleven JVMs and four datastores; that is a real
monthly bill for something almost nobody clicks, and a link that 502s in month two is worse than no
link. If a specific interviewer asks, bring it up for that week — see
[DEPLOY.md](DEPLOY.md).

## Two things to avoid

**Do not claim it is production-ready.** It is not, it says so, and the person opposite will find
the seam faster than you can talk past it. "No real money moves — both acquirers are simulated" is
a stronger sentence than any hedge, because it tells them you know exactly where the edge is.

**Do not open the code first.** Show it working, then show why it works. Interviewers who see a
payment captured and an acquirer killed will read the code afterwards on their own.
