package com.openpay.gateway.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openpay.security.RedisFixedWindowLimiter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfiguration {

    /**
     * After every authentication filter the gateway registers (correlation id, CORS, JWT, API key),
     * so a request that reaches this one is either authenticated or was never required to be. There
     * is nothing left to charge an unauthenticated caller's count against.
     */
    private static final int RATE_LIMIT_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 20;

    @Bean
    public RedisFixedWindowLimiter redisFixedWindowLimiter(StringRedisTemplate redisTemplate) {
        return new RedisFixedWindowLimiter(redisTemplate);
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilter(
            RedisFixedWindowLimiter limiter, RateLimitProperties properties, ObjectMapper objectMapper) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new RateLimitFilter(limiter, properties, objectMapper));
        registration.setOrder(RATE_LIMIT_FILTER_ORDER);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
