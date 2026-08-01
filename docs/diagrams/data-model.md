# Data model

Nine databases, one per service. No service reads another's, and there is not a single foreign key
that crosses a service boundary — so the relationships drawn *between* the boxes below are
application-level references, held together by ids and by events rather than by the database.

Drawn per service, because that is how it is enforced.

## auth-service — `openpay_auth`

```mermaid
erDiagram
    api_keys {
        uuid id PK
        uuid merchant_id "no FK: another service owns merchants"
        varchar key_prefix UK "the lookup key"
        varchar key_hash "SHA-256; the key itself is never stored"
        varchar scope "payments:read | payments:write"
        varchar status
        timestamptz expires_at
        timestamptz last_used_at
    }
    users {
        uuid id PK
        uuid merchant_id
        varchar email UK
        varchar password_hash "BCrypt"
        varchar role "MERCHANT_ADMIN | MERCHANT_VIEWER"
        varchar status
        timestamptz last_login_at
    }
    audit_logs {
        uuid id PK
        varchar action
        varchar actor "free text, not a FK"
        varchar subject
        uuid merchant_id
        boolean succeeded
        varchar source_ip
        varchar correlation_id
        timestamptz occurred_at
    }
```

`api_keys` stores a prefix and a hash, never the key. The prefix is what makes lookup a single
indexed read without the hash having to be reversible.

`audit_logs.actor` is free text rather than a foreign key. The actor may be someone this service has
no row for — an unknown email at a failed login, an operator holding a token — and a log that can
only describe actors it already knows about is missing exactly the entries worth having.

## merchant-service — `openpay_merchant`

```mermaid
erDiagram
    merchants {
        uuid id PK
        varchar merchant_code UK
        varchar legal_name
        varchar status
        varchar webhook_url "checked against the SSRF policy before storage"
        varchar webhook_secret "returned once, on rotation"
        char default_currency
    }
    audit_logs {
        uuid id PK
        varchar action "MERCHANT_CREATED | WEBHOOK_SECRET_ROTATED"
        varchar actor
        uuid merchant_id
        timestamptz occurred_at
    }
```

A second `audit_logs`, not a shared one. A central audit table would make every service's writes
depend on a schema none of them owns, and would be the single outage that stops the whole platform
recording anything.

## payment-service — `openpay_payment`

```mermaid
erDiagram
    payments ||--o{ payment_events : "records"
    payments ||--o{ refunds : "may have"

    payments {
        uuid id PK
        uuid merchant_id
        varchar idempotency_key "UK with merchant_id"
        varchar request_fingerprint "SHA-256 of amount and currency"
        bigint amount "minor units"
        char currency
        varchar status
        varchar fraud_status "ALLOWED | HELD | BLOCKED | UNSCREENED"
        varchar method_type "no token, no PAN, no VPA"
        integer version "optimistic lock"
    }
    payment_events {
        uuid id PK
        uuid payment_id FK
        varchar type
        jsonb payload "explicit shape, so it does not drift with the entity"
    }
    refunds {
        uuid id PK
        uuid payment_id FK
        varchar idempotency_key
        bigint amount
        varchar status
    }
    outbox_events {
        uuid id PK
        varchar topic
        jsonb payload
        timestamptz published_at "null until relayed"
        integer attempts
    }
```

`(merchant_id, idempotency_key)` is unique, and that constraint is the concurrency control: two
racing requests both attempt the insert, one wins, and the loser re-reads and returns the winner's
payment.

`request_fingerprint` is what makes replaying a key with a *different* body a `409` rather than a
silently wrong `200`.

`fraud_status` is a separate column rather than extra statuses — see
[state-machine.md](state-machine.md).

## provider-router-service — `openpay_router`

```mermaid
erDiagram
    provider_routing_rules {
        uuid id PK
        varchar provider_name
        varchar base_url
        integer priority "lowest first"
        boolean enabled
        uuid merchant_id "null = every merchant"
        char currency "null = every currency"
        bigint min_amount "half-open band"
        bigint max_amount
    }
    provider_transactions {
        uuid id PK
        uuid payment_id
        uuid merchant_id
        varchar provider_name
        integer attempt_no
        varchar status "ACCEPTED | FAILED"
        varchar provider_reference
        varchar failure_reason
    }
```

`provider_transactions` is why a payment that ended up on the second acquirer can still show what
was tried first and why it was abandoned. The row is written *before* the call, not after.

The unique constraint on the rules table is `NULLS NOT DISTINCT`, because every narrowing column is
nullable and the general case is all three null — under the default, NULLs never collide and the
constraint would permit unlimited duplicate general rules.

## fraud-service — `openpay_fraud`

```mermaid
erDiagram
    fraud_rules {
        uuid id PK
        varchar name UK
        varchar rule_type "AMOUNT_OVER | VELOCITY_COUNT | REPEATED_AMOUNT"
        bigint threshold
        integer window_seconds "velocity rules only"
        char currency "required for AMOUNT_OVER"
        varchar action "REVIEW | BLOCK"
        integer priority "first match wins"
        boolean enabled
    }
    fraud_decisions {
        uuid id PK
        uuid payment_id UK "one decision per payment"
        uuid merchant_id
        bigint amount
        varchar outcome "ALLOW | REVIEW | BLOCK"
        varchar rule_name "the name, not a FK"
        varchar resolved_outcome "set when a human closes a review"
        varchar resolved_by
        timestamptz resolved_at
    }
```

`rule_name` is stored rather than a foreign key to `fraud_rules`, and rules are disabled rather than
deleted, for the same reason: a deleted rule would take with it the only explanation for every
decision that cites it.

The resolution columns sit beside the original outcome rather than overwriting it. How a payment was
first judged and what an operator decided about it are two different facts, and merging them
destroys the only record that a review ever happened.

## ledger-service — `openpay_ledger`

```mermaid
erDiagram
    ledger_accounts ||--o{ ledger_entries : "posted to"
    ledger_transactions ||--o{ ledger_entries : "balanced within"

    ledger_accounts {
        uuid id PK
        varchar account_code UK
        varchar account_type "ASSET | LIABILITY | REVENUE | EXPENSE"
    }
    ledger_transactions {
        uuid id PK
        varchar reference_id "the payment or settlement it explains"
        varchar reference_type
    }
    ledger_entries {
        uuid id PK
        uuid transaction_id FK
        uuid account_id FK
        varchar direction "DEBIT | CREDIT"
        bigint amount "minor units, always positive"
    }
```

Append-only, enforced by the database rather than by convention: there is no `UPDATE` or `DELETE`
path to `ledger_entries` in the application, and a balance is derived from the journal rather than
stored beside it. A stored balance is a second truth that can disagree with the entries.

## settlement-service — `openpay_settlement`

```mermaid
erDiagram
    settlements ||--o{ settlement_items : "batches"

    settlement_items {
        uuid id PK
        uuid merchant_id
        uuid payment_id
        uuid refund_id "null for a capture"
        varchar item_type "CAPTURE | REFUND"
        bigint gross_amount "negative for a refund"
        bigint fee_amount "zero for a refund"
        bigint net_amount
        varchar status "PENDING | SETTLED"
        uuid settlement_id FK "set when it joins a batch"
    }
    settlements {
        uuid id PK
        uuid merchant_id
        char currency
        date settlement_date "UK with merchant and currency"
        bigint gross_amount
        bigint net_amount
        integer item_count
        varchar status
    }
```

The two levels are what let a payment be accrued the moment it is captured but paid out on a
schedule, and what makes a payout auditable back to the payments inside it.

`(merchant_id, currency, settlement_date)` is unique so that running the job twice cannot produce
two payouts for the same money.

## webhook-service and notification-service

```mermaid
erDiagram
    provider_webhook_events {
        uuid id PK
        varchar provider
        varchar event_id UK "the acquirer's own id, per provider"
        jsonb payload
        timestamptz received_at
    }
    merchant_webhook_deliveries {
        uuid id PK
        uuid merchant_id
        varchar event_type
        varchar target_url
        varchar status "PENDING | DELIVERED | FAILED"
        integer attempts
        integer response_status
        timestamptz next_attempt_at
    }
```

`event_id` unique per provider is the deduplication. Acquirers redeliver, and a duplicate capture
that got through would credit a merchant twice.

## What money looks like everywhere

`BIGINT`, in the currency's smallest unit — paise, cents. Never a floating point type, and never a
`DECIMAL` that some code path might read as a double. Jackson is configured to reject a fractional
amount outright rather than truncate `10.99` to `10`.
