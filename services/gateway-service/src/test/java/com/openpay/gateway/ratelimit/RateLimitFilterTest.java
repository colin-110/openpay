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
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RateLimitFilterTest {

    private final RedisFixedWindowLimiter limiter = mock(RedisFixedWindowLimiter.class);
    private final RateLimitProperties properties = new RateLimitProperties();
    // The real ObjectMapper Spring injects auto-registers the JSR-310 module; a bare `new
    // ObjectMapper()` here does not, and SecurityErrorResponse carries an OffsetDateTime.
    private final RateLimitFilter filter =
            new RateLimitFilter(limiter, properties, new ObjectMapper().registerModule(new JavaTimeModule()));

    @Test
    void letsAnAuthenticatedWriteThroughWhenUnderTheLimit() throws Exception {
        when(limiter.tryConsume(any(), anyInt(), any())).thenReturn(true);
        MockHttpServletRequest request = authenticatedPost();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void refusesWithA429AndRetryAfterOnceTheLimitIsCrossed() throws Exception {
        when(limiter.tryConsume(any(), anyInt(), any())).thenReturn(false);
        MockHttpServletRequest request = authenticatedPost();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isNotBlank();
        assertThat(response.getContentAsString()).contains("rate_limited");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void keysTheCounterByMerchantSoOneMerchantCannotExhaustAnothersBudget() throws Exception {
        UUID merchantId = UUID.randomUUID();
        when(limiter.tryConsume(any(), anyInt(), any())).thenReturn(true);
        MockHttpServletRequest request = authenticatedPost(merchantId);

        filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));

        verify(limiter).tryConsume(eq("ratelimit:write:" + merchantId), anyInt(), any(Duration.class));
    }

    @Test
    void doesNotChargeAReadAgainstTheWriteBudget() throws Exception {
        MockHttpServletRequest request = authenticatedRequest("GET");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(limiter, never()).tryConsume(any(), anyInt(), any());
    }

    @Test
    void passesThroughWithNoPrincipalRatherThanThrowing() throws Exception {
        // An invalid credential was already rejected by an upstream filter, or the path never
        // required one. Either way there is no merchant to charge, and this filter must not be
        // the thing that turns that into an error.
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/payments");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(limiter, never()).tryConsume(any(), anyInt(), any());
    }

    @Test
    void aDisabledLimiterNeverConsultsRedisAtAll() throws Exception {
        properties.setEnabled(false);
        MockHttpServletRequest request = authenticatedPost();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(limiter, never()).tryConsume(any(), anyInt(), any());
    }

    private MockHttpServletRequest authenticatedPost() {
        return authenticatedPost(UUID.randomUUID());
    }

    private MockHttpServletRequest authenticatedPost(UUID merchantId) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/payments");
        request.setAttribute(
                ApiKeyAuthenticationFilter.PRINCIPAL_ATTRIBUTE,
                new ApiKeyPrincipal(merchantId, "payments:write"));
        return request;
    }

    private MockHttpServletRequest authenticatedRequest(String method) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/v1/payments");
        request.setAttribute(
                ApiKeyAuthenticationFilter.PRINCIPAL_ATTRIBUTE,
                new ApiKeyPrincipal(UUID.randomUUID(), "payments:write"));
        return request;
    }
}
