package com.openpay.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Accepts a dashboard session token on the same paths that accept an API key.
 *
 * <p>Runs before the API key filter and, on success, publishes the same {@link ApiKeyPrincipal}.
 * Downstream code therefore never learns which credential was used: a payment read is scoped to a
 * merchant whether a server presented an API key or a person presented a session.
 *
 * <p>A request with no bearer token passes through untouched so the API key filter can handle it.
 * Only a token that is present and invalid is rejected here, because silently ignoring a malformed
 * token would let a caller fall through to no authentication at all.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final SecretKey key;
    private final List<String> protectedPaths;
    private final SecurityErrorWriter errorWriter;

    public JwtAuthenticationFilter(String secret, List<String> protectedPaths, ObjectMapper objectMapper) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.protectedPaths = List.copyOf(protectedPaths);
        this.errorWriter = new SecurityErrorWriter(objectMapper);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return protectedPaths.stream().noneMatch(uri::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader(AUTHORIZATION_HEADER);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(header.substring(BEARER_PREFIX.length()).trim())
                    .getPayload();

            UUID merchantId = UUID.fromString(claims.get("merchantId", String.class));
            String role = claims.get("role", String.class);

            request.setAttribute(
                    ApiKeyAuthenticationFilter.PRINCIPAL_ATTRIBUTE, new ApiKeyPrincipal(merchantId, role));
            chain.doFilter(request, response);

        } catch (JwtException | IllegalArgumentException exception) {
            // Covers a bad signature, an expired token, and a malformed one alike: from the
            // caller's point of view the session is simply not usable.
            log.warn("Rejected request to {} with an invalid session token", request.getRequestURI());
            errorWriter.write(response, HttpStatus.UNAUTHORIZED, "invalid_session",
                    "Session token is invalid or expired", request.getRequestURI());
        }
    }
}
