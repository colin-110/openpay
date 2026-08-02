package com.openpay.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A refresh token, stored the way {@code api_keys} stores its secret: a SHA-256 hash, never the
 * plaintext. Holding one is exactly as sensitive as holding a password — it mints fresh sessions
 * indefinitely until it expires or is revoked.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    /**
     * The token that replaced this one, set the moment this one is used. Distinguishes a normal
     * revocation ("logged out") from theft ("this exact token was used a second time after it had
     * already been rotated") — the second case is the one that revokes the whole chain, not just
     * this row.
     */
    @Column(name = "replaced_by")
    private UUID replacedBy;

    protected RefreshToken() {
        // JPA only
    }

    public RefreshToken(UUID userId, String tokenHash, OffsetDateTime expiresAt) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.createdAt = OffsetDateTime.now();
        this.expiresAt = expiresAt;
    }

    public boolean isUsable() {
        return revokedAt == null && expiresAt.isAfter(OffsetDateTime.now());
    }

    /** True only for a token that was rotated away rather than explicitly logged out or expired. */
    public boolean wasRotatedAway() {
        return revokedAt != null && replacedBy != null;
    }

    /** Revokes this token as part of rotation, recording what replaced it. */
    public void rotateTo(UUID newTokenId) {
        this.revokedAt = OffsetDateTime.now();
        this.replacedBy = newTokenId;
    }

    /** Revokes this token outright — logout, or a theft response. Nothing replaces it. */
    public void revoke() {
        this.revokedAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public OffsetDateTime getRevokedAt() {
        return revokedAt;
    }

    public UUID getReplacedBy() {
        return replacedBy;
    }
}
