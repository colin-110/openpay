# Creating a payment

The synchronous half. Everything after the `201` happens on its own — see
[callback-sequence.md](callback-sequence.md).

```mermaid
sequenceDiagram
    autonumber
    participant M as Merchant
    participant G as gateway-service
    participant A as auth-service
    participant P as payment-service
    participant F as fraud-service
    participant DB as payments DB
    participant K as Kafka

    M->>G: POST /api/v1/payments<br/>X-Api-Key, Idempotency-Key
    G->>A: POST /api/v1/auth/validate-key
    A-->>G: merchantId + scope
    Note over G,A: Merchant identity comes from the validated key.<br/>A client-supplied header is never trusted.

    G->>P: proxy, with the principal attached
    P->>P: requireWrite("create payments")

    P->>DB: SELECT by (merchant, idempotency key)
    alt key already used
        DB-->>P: the original payment
        P-->>M: 200 + original payment
        Note right of P: A replay is answered from storage.<br/>Screening is not re-run.
    else new
        P->>P: mint the payment id
        Note right of P: Minted here, not by the database,<br/>so screening can be keyed on it<br/>before anything is written.

        P->>F: POST /internal/fraud/checks
        alt fraud-service unreachable
            F--xP: timeout
            Note over P,F: Fails open by default: the payment is recorded<br/>UNSCREENED, not ALLOWED, so the window is<br/>visible afterwards. See ADR-0003.
        end
        F-->>P: ALLOW | REVIEW | BLOCK

        alt BLOCK
            P-->>M: 422 payment_blocked
            Note right of P: Nothing is persisted. A refused payment is<br/>not a payment that happened.
        else REVIEW
            P->>DB: INSERT payment (fraud_status = HELD)
            Note right of P: No outbox row. Publishing is what starts<br/>routing, so a held payment reaches no acquirer.
            P-->>M: 201, fraudStatus HELD
        else ALLOW
            P->>DB: INSERT payment + INSERT outbox row
            Note over P,DB: One transaction. The event cannot escape<br/>without the payment, and the payment cannot<br/>commit without the event.
            P-->>M: 201 Created
            P->>K: relay publishes payment.created.v1
        end
    end
```

## The three things worth noticing

**The idempotency check comes before screening.** A retried creation gets the answer it already got.
Re-running the rules would evaluate against a velocity window that has moved since, so the same
payment could be allowed on one attempt and blocked on the next.

**The payment id is minted in the application.** That is what makes the fraud gate idempotent: the
decision is keyed on an id that exists before any row does.

**The outbox row and the payment row commit together.** This is the whole reason for the outbox.
Publishing to Kafka after the commit leaves a window where the payment exists and the event does
not; publishing before leaves one where the event exists and the payment does not. Both windows are
lost or duplicated money — see [ADR-0001](../adrs/0001-transactional-outbox.md).

## Concurrency

Two requests racing on the same idempotency key both reach the insert. The unique constraint on
`(merchant_id, idempotency_key)` picks a winner; the loser catches the violation, re-reads, and
returns the winner's payment. No lock, and no window where two payments exist for one key.
