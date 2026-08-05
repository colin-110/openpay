package com.openpay.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.openpay.security.ApiKeyAuthenticationFilter;
import com.openpay.security.ApiKeyPrincipal;
import com.openpay.security.RedisFixedWindowLimiter;
import jakarta.servlet.FilterChain;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Tokenisation is the one authenticated write anybody on the internet can make, because the key it
 * takes is printed in a checkout page on purpose. That makes a per-merchant limit the wrong shape:
 * it is a single budget shared by every visitor, so one of them can spend all of it, and it cannot
 * distinguish a customer buying a kettle from a script working through stolen card numbers.
 */
class PublishableRateLimitTest {

    private final RedisFixedWindowLimiter limiter = mock(RedisFixedWindowLimiter.class);
    private final RateLimitProperties properties = new RateLimitProperties();
    private final RateLimitFilter filter =
            new RateLimitFilter(limiter, properties, new ObjectMapper().registerModule(new JavaTimeModule()));

    private MockHttpServletRequest tokenise(String callerIp) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/tokens");
        request.setRemoteAddr(callerIp);
        request.setAttribute(
                ApiKeyAuthenticationFilter.PRINCIPAL_ATTRIBUTE,
                new ApiKeyPrincipal(UUID.randomUUID(), ApiKeyPrincipal.PUBLISHABLE_SCOPE));
        return request;
    }

    @Test
    void chargesTokenisationToTheCallerAsWellAsTheMerchant() throws Exception {
        when(limiter.tryConsume(any(), anyInt(), any())).thenReturn(true);

        filter.doFilter(tokenise("203.0.113.7"), new MockHttpServletResponse(), mock(FilterChain.class));

        // Both buckets. The per-caller one stops one visitor draining the shop; the per-merchant
        // one still bounds what the shop as a whole can generate.
        verify(limiter).tryConsume(eq("ratelimit:tokenise:203.0.113.7"),
                eq(properties.getPublishableLimit()), eq(properties.getPublishableWindow()));
        verify(limiter).tryConsume(startsWith("ratelimit:write:"), eq(properties.getLimit()), any());
    }

    @Test
    void refusesOneCallerWithoutSpendingTheMerchantsBudget() throws Exception {
        when(limiter.tryConsume(startsWith("ratelimit:tokenise:"), anyInt(), any())).thenReturn(false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(tokenise("203.0.113.7"), response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        verify(chain, never()).doFilter(any(), any());
        // The point of the ordering: a refused caller must not also consume the shared budget, or
        // an attacker locked out by their own limit could still take the shop down with the other.
        verify(limiter, never()).tryConsume(startsWith("ratelimit:write:"), anyInt(), any());
    }

    @Test
    void oneCallerBeingBlockedDoesNotBlockAnother() throws Exception {
        when(limiter.tryConsume(eq("ratelimit:tokenise:198.51.100.9"), anyInt(), any())).thenReturn(false);
        when(limiter.tryConsume(eq("ratelimit:tokenise:203.0.113.7"), anyInt(), any())).thenReturn(true);
        when(limiter.tryConsume(startsWith("ratelimit:write:"), anyInt(), any())).thenReturn(true);

        MockHttpServletResponse blocked = new MockHttpServletResponse();
        filter.doFilter(tokenise("198.51.100.9"), blocked, mock(FilterChain.class));

        FilterChain allowedChain = mock(FilterChain.class);
        MockHttpServletRequest allowed = tokenise("203.0.113.7");
        filter.doFilter(allowed, new MockHttpServletResponse(), allowedChain);

        assertThat(blocked.getStatus()).isEqualTo(429);
        // The whole reason for keying on the caller: a real customer still checks out while the
        // abusive one is locked out.
        verify(allowedChain).doFilter(any(), any());
    }

    @Test
    void aSecretKeyIsNotChargedToAnAddress() throws Exception {
        when(limiter.tryConsume(any(), anyInt(), any())).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/payments");
        request.setRemoteAddr("203.0.113.7");
        request.setAttribute(
                ApiKeyAuthenticationFilter.PRINCIPAL_ATTRIBUTE,
                new ApiKeyPrincipal(UUID.randomUUID(), "payments:write"));

        filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));

        // A merchant's server is one caller by definition, and limiting it by address would cap the
        // whole integration at a shopper's rate.
        verify(limiter, never()).tryConsume(startsWith("ratelimit:tokenise:"), anyInt(), any());
    }

    @Test
    void ignoresAForwardedHeaderNobodyPromisedToOverwrite() throws Exception {
        when(limiter.tryConsume(any(), anyInt(), any())).thenReturn(true);
        MockHttpServletRequest request = tokenise("203.0.113.7");
        request.addHeader("X-Forwarded-For", "1.2.3.4");

        filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));

        // Off by default. Believing a client-supplied header when nothing is overwriting it would
        // let any caller reset their own bucket by changing one value — a control in appearance
        // only, which is worse than none because it invites trust.
        verify(limiter).tryConsume(eq("ratelimit:tokenise:203.0.113.7"), anyInt(), any());
    }

    @Test
    void believesTheForwardedHeaderOnceTheDeploymentSaysSomethingSetsIt() throws Exception {
        properties.setTrustForwardedFor(true);
        when(limiter.tryConsume(any(), anyInt(), any())).thenReturn(true);
        MockHttpServletRequest request = tokenise("10.0.0.1");
        // A proxy appends, so the left-most entry is the original client and the rest are hops.
        request.addHeader("X-Forwarded-For", "198.51.100.9, 10.0.0.1");

        filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));

        verify(limiter).tryConsume(eq("ratelimit:tokenise:198.51.100.9"), anyInt(), any());
    }
}
