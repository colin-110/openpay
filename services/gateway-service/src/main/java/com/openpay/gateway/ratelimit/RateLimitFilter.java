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

        String key = "ratelimit:write:" + principal.merchantId();
        if (limiter.tryConsume(key, properties.getLimit(), properties.getWindow())) {
            chain.doFilter(request, response);
            return;
        }

        long retryAfterSeconds = Math.max(1, properties.getWindow().toSeconds());
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        errorWriter.write(response, HttpStatus.TOO_MANY_REQUESTS, "rate_limited",
                "Too many requests. Retry after " + retryAfterSeconds + "s.", request.getRequestURI());
    }
}
