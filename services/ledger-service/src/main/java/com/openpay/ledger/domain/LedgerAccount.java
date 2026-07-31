package com.openpay.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "ledger_accounts")
public class LedgerAccount {

    @Id
    private UUID id;

    @Column(name = "account_code", nullable = false, length = 50)
    private String accountCode;

    /** Null for platform-owned accounts such as gateway clearing. */
    @Column(name = "merchant_id")
    private UUID merchantId;

    @Column(nullable = false, length = 3, columnDefinition = "bpchar")
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 20)
    private AccountType accountType;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected LedgerAccount() {
        // JPA only
    }

    public LedgerAccount(String accountCode, UUID merchantId, String currency, AccountType accountType) {
        this.id = UUID.randomUUID();
        this.accountCode = accountCode;
        this.merchantId = merchantId;
        this.currency = currency;
        this.accountType = accountType;
        this.createdAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public String getAccountCode() {
        return accountCode;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public String getCurrency() {
        return currency;
    }

    public AccountType getAccountType() {
        return accountType;
    }
}
