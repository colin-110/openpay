package com.openpay.auth.application;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Issues the dashboard's session tokens.
 *
 * <p>Symmetric HS256 rather than a signed-by-private-key scheme, because every verifier here is one
 * of our own services and they can share a secret. That choice would need revisiting the moment a
 * third party had to verify a token without being able to mint one.
 *
 * <p>Tokens are short-lived and carry no revocation list. That is a deliberate trade: statelessness
 * is what lets any service verify a token without calling auth-service, and the cost is that a
 * disabled user stays valid until their token expires. The expiry is set short enough to bound
 * that window.
 */
@Component
public class JwtIssuer {

    private static final int MINIMUM_SECRET_BYTES = 32;

    private final SecretKey key;
    private final Duration tokenTtl;
    private final String issuer;

    public JwtIssuer(
            @Value("${openpay.jwt.secret:}") String secret,
            @Value("${openpay.jwt.ttl:PT1H}") Duration tokenTtl,
            @Value("${openpay.jwt.issuer:openpay}") String issuer) {

        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MINIMUM_SECRET_BYTES) {
            // Fails at startup rather than issuing forgeable tokens. HS256 with a short key is
            // brute-forcible, and a signing key is not something to default.
            throw new IllegalStateException(
                    "openpay.jwt.secret must be at least " + MINIMUM_SECRET_BYTES
                            + " bytes. Set OPENPAY_JWT_SECRET.");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.tokenTtl = tokenTtl;
        this.issuer = issuer;
    }

    public IssuedToken issue(java.util.UUID userId, java.util.UUID merchantId, String email, String role) {
        Instant now = Instant.now();
        Instant expiry = now.plus(tokenTtl);

        String token = Jwts.builder()
                .issuer(issuer)
                .subject(userId.toString())
                // merchantId is in the token so downstream services can scope reads without
                // calling back here on every request.
                .claim("merchantId", merchantId.toString())
                .claim("email", email)
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(key)
                .compact();

        return new IssuedToken(token, expiry);
    }

    public record IssuedToken(String token, Instant expiresAt) {
    }
}
