# From `payment.created.v1` to `CAPTURED`

The asynchronous half. Nothing here is driven by the merchant; a full local run takes about three
seconds.

```mermaid
sequenceDiagram
    autonumber
    participant K as Kafka
    participant R as provider-router-service
    participant A as mock-bank-a
    participant B as mock-bank-b
    participant W as webhook-service
    participant P as payment-service
    participant L as ledger-service
    participant S as settlement-service
    participant N as notification-service
    participant M as Merchant

    K->>R: payment.created.v1
    R->>R: already routed? (unique payment id)
    Note right of R: A redelivered event must not<br/>produce a second charge attempt.

    R->>R: candidates from provider_routing_rules,<br/>skipping open circuit breakers

    R->>A: POST /provider/payments
    alt accepted
        A-->>R: provider reference
        R->>K: payment.provider-dispatched.v1
        K->>P: PENDING_PROVIDER
    else refused or hung
        A--xR: failure, breaker records it
        R->>B: POST /provider/payments
        alt accepted
            B-->>R: provider reference
            R->>K: payment.provider-dispatched.v1
        else every acquirer exhausted
            R->>K: provider.callback-received.v1 (FAILED)
            Note over R,K: Reported as a callback so payment-service has<br/>exactly one path to a terminal state, whether<br/>the answer came from an acquirer or from us.
        end
    end

    A-->>W: signed callback (HMAC over timestamp.body)
    W->>W: verify signature, reject outside 5 minutes,<br/>deduplicate on the acquirer's event id
    W->>K: provider.callback-received.v1
    K->>P: AUTHORIZED, then CAPTURED
    P->>K: payment.status-updated.v1

    par
        K->>L: double-entry posting
    and
        K->>S: accrue the merchant's payable
    and
        K->>N: deliver a signed webhook
        N->>M: POST the merchant's webhook URL
    end
```

## Why the timestamp is inside the signature

The HMAC covers `timestamp.body`, not `body`. Signing the body alone means a captured callback stays
replayable forever: an attacker resends it with a fresh timestamp header and the signature still
verifies. Binding the timestamp into the signed material is what makes the five-minute window mean
anything.

Deduplication on the acquirer's event id is the second half. Acquirers genuinely redeliver, so a
duplicate has to be safe — and a duplicate *capture* that got through would credit a merchant twice.

## Why routing failure is reported as a callback

Running out of acquirers is an outcome, not an absence of one. If it were not published, a payment
nobody could route would sit in `CREATED` forever with nothing to explain why. Publishing it on the
same topic as a real acquirer answer means payment-service has one path that moves a payment to a
terminal state, rather than two that must be kept in agreement.

## Ordering

Routing notifications and provider callbacks travel on different topics, so nothing orders them
relative to each other. A callback processed before the dispatch notification is normal, which is
why `CREATED` accepts `AUTHORIZED` and `CAPTURED` directly rather than only `PENDING_PROVIDER` —
refusing them would drop the real outcome and strand the payment when the late notification arrived.
