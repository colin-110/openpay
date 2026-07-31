package com.openpay.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(nullable = false, length = 255)
    private String email;

    /** BCrypt hash. The plaintext password is never stored or logged. */
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(nullable = false, length = 30)
    private String role;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "last_login_at")
    private OffsetDateTime lastLoginAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected User() {
        // JPA only
    }

    public User(UUID merchantId, String email, String passwordHash, String role) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        // Normalised here rather than at the call site, so the unique constraint cannot be
        // defeated by capitalisation.
        this.email = email.trim().toLowerCase();
        this.passwordHash = passwordHash;
        this.role = role;
        this.status = "ACTIVE";
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = this.createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getRole() {
        return role;
    }

    public String getStatus() {
        return status;
    }

    public OffsetDateTime getLastLoginAt() {
        return lastLoginAt;
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    public void recordLogin() {
        this.lastLoginAt = OffsetDateTime.now();
        this.updatedAt = this.lastLoginAt;
    }
}
