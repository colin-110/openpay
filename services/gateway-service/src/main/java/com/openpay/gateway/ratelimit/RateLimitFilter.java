package com.openpay.gateway.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openpay.security.ApiKeyAuthenticationFilter;
import com.openpay.security.ApiKeyPrincipal;
import com.openpay.security.RedisFixedWindowLimiter;
import com.openpay.security.SecurityErrorWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Caps how fast one merchant can write, so one integration gone wrong stays that merchant's
 * problem instead of becoming a platform-wide incident.
 *
 * <p>Before this, nothing stood between a valid API key and as many payment creations as the
 * caller could open connections for. Every one of those fans out to Kafka, the router, an
 * acquirer, the ledger, settlement, and an outbound webhook — a single misbehaving integration was
 * capable of costing every other merchant's payments the resources it was consuming.
 *
 * <p>Runs after authentication, on purpose. By the time this filter sees a request, an invalid
 * credential has already been rejected and never reaches here — so the limiter counts real
 * merchant traffic, not somebody guessing at API keys.
 *
 * <p>Scoped to mutating requests. A read costs the platform far less than a write, which is what
 * touches Kafka and every downstream service; limiting it too would not track the actual risk.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final RedisFixedWindowLimiter limiter;
    private final RateLimitProperties properties;
    private final SecurityErrorWriter errorWriter;

    public RateLimitFilter(
            RedisFixedWindowLimiter limiter, RateLimitProperties properties, ObjectMapper objectMapper) {
        this.limiter = limiter;
        this.properties = properties;
        this.errorWriter = new SecurityErrorWriter(objectMapper);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.isEnabled() || SAFE_METHODS.contains(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        Object attribute = request.getAttribute(ApiKeyAuthenticationFilter.PRINCIPAL_ATTRIBUTE);
        if (!(attribute instanceof ApiKeyPrincipal principal)) {
            // No authenticated merchant to charge this request against. Either the credential was
            // already rejected upstream (and the response is already written), or this path never
            // required one, in which case it is not merchant write traffic to begin with.
            chain.doFilter(request, response);
            return;
        }

        // A publishable key is limited by *caller*, not only by merchant, and that distinction is
        // the whole reason this branch exists.
        //
        // Every other credential here belongs to one merchant's server, so charging its traffic to
        // the merchant is exactly right. A publishable key belongs to a checkout page, is readable
        // by anyone who opens it, and is presented by every visitor on the internet — so a
        // merchant-scoped bucket is one shared budget that any single visitor can drain, taking the
        // shop down for everyone else. It is also the wrong shape for the attack this endpoint
        // actually attracts: card testing, where stolen numbers are validated in bulk against a
        // public tokenisation endpoint. A per-merchant counter cannot tell one customer buying a
        // kettle from one script working through a list.
        //
        // Both limits apply. The per-IP one stops a single caller; the per-merchant one below still
        // bounds what the shop as a whole can generate.
        if (principal.isPublishable()
                && !limiter.tryConsume("ratelimit:tokenise:" + callerAddress(request),
                        properties.getPublishableLimit(), properties.getPublishableWindow())) {
            refuse(request, response, properties.getPublishableWindow().toSeconds());
            return;
        }

        String key = "ratelimit:write:" + principal.merchantId();
        if (limiter.tryConsume(key, properties.getLimit(), properties.getWindow())) {
            chain.doFilter(request, response);
            return;
        }

        refuse(request, response, properties.getWindow().toSeconds());
    }

    private void refuse(HttpServletRequest request, HttpServletResponse response, long windowSeconds)
            throws IOException {
        long retryAfterSeconds = Math.max(1, windowSeconds);
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        errorWriter.write(response, HttpStatus.TOO_MANY_REQUESTS, "rate_limited",
                "Too many requests. Retry after " + retryAfterSeconds + "s.", request.getRequestURI());
    }

    /**
     * Who to charge a publishable-key request to.
     *
     * <p>{@code X-Forwarded-For} is a client-supplied header and trivially spoofable, so it is only
     * read when the deployment says something in front is overwriting it. Trusting it by default
     * would make this limiter bypassable by adding one header, which is worse than not having it —
     * it would look like a control while being none.
     *
     * <p>The <em>first</em> entry when trusted: a proxy appends, so the left-most value is the
     * original client. Behind a proxy that overwrites rather than appends this is the same thing.
     */
    private String callerAddress(HttpServletRequest request) {
        if (properties.isTrustForwardedFor()) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                int comma = forwarded.indexOf(',');
                return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
            }
        }
        return request.getRemoteAddr();
    }
}
