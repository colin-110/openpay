package com.openpay.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.client.RestClient;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * Wires API-key and admin-token authentication into any service that puts this library on the
 * classpath. Services opt in per path via {@code openpay.security.api-key-paths} and
 * {@code openpay.security.admin-paths}.
 */
@AutoConfiguration
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityAutoConfiguration {

    /** Runs just after the correlation-id filter so rejected requests still carry a correlation id. */
    private static final int SECURITY_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    /**
     * A registration for a filter this service has not been configured to use.
     *
     * <p>The filter itself is a pass-through rather than null: Spring asks every registration to
     * describe itself at startup <em>before</em> it looks at the enabled flag, and a null filter
     * there fails the whole web server rather than quietly skipping the registration.
     */
    private static FilterRegistrationBean<Filter> notRegistered() {
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>();
        registration.setFilter((request, response, chain) -> chain.doFilter(request, response));
        registration.setEnabled(false);
        return registration;
    }

    /**
     * A CORS preflight carries no credentials, so it has to be answered before anything demands
     * one. Any later and the browser would be told the request was unauthorised and never send
     * the real one.
     */
    @Bean
    public FilterRegistrationBean<Filter> corsFilter(SecurityProperties properties) {
        if (properties.getAllowedOrigins().isEmpty()) {
            return notRegistered();
        }

        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>();
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(properties.getAllowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of(
                "Authorization", "Content-Type", "Idempotency-Key", "X-Api-Key", "X-Correlation-Id"));
        cors.setExposedHeaders(List.of("Location", "X-Correlation-Id"));
        // Sessions travel in the Authorization header, not a cookie, so the browser never needs
        // to attach credentials — and not asking for them keeps wildcard origins impossible.
        cors.setAllowCredentials(false);
        cors.setMaxAge(Duration.ofHours(1));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);

        registration.setFilter(new CorsFilter(source));
        registration.setOrder(SECURITY_FILTER_ORDER - 2);
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean(AuthServiceClient.class)
    public AuthServiceClient authServiceClient(SecurityProperties properties) {
        // Pooled: this runs on every authenticated request that misses the cache below, and an
        // unpooled client would open a TCP connection to auth-service for each one.
        RestClient restClient = RestClient.builder()
                .baseUrl(properties.getAuthBaseUrl())
                .requestFactory(InternalHttpClients.pooled(
                        properties.getConnectTimeout(), properties.getReadTimeout(), 100))
                .build();
        AuthServiceClient client = new HttpAuthServiceClient(restClient);

        // Wrapped rather than built into the HTTP client, so "how do we ask auth-service" and "how
        // long do we trust the answer" stay separable — and so a TTL of zero gives back exactly the
        // uncached client, with no caching code on the path at all.
        Duration cacheTtl = properties.getApiKeyCacheTtl();
        if (cacheTtl == null || cacheTtl.isZero() || cacheTtl.isNegative()) {
            return client;
        }
        return new CachingAuthServiceClient(client, cacheTtl);
    }

    /**
     * Registered only when a secret is configured, so a service with no dashboard traffic is not
     * made to hold a signing key it never uses.
     */
    @Bean
    public FilterRegistrationBean<Filter> jwtAuthenticationFilter(
            SecurityProperties properties, ObjectMapper objectMapper) {
        if (properties.getJwtSecret() == null || properties.getJwtSecret().isBlank()) {
            return notRegistered();
        }

        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new JwtAuthenticationFilter(
                properties.getJwtSecret(), properties.getApiKeyPaths(), objectMapper));
        // Ahead of the API key filter, which then sees an already-authenticated request.
        registration.setOrder(SECURITY_FILTER_ORDER - 1);
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Bean
    public FilterRegistrationBean<ApiKeyAuthenticationFilter> apiKeyAuthenticationFilter(
            AuthServiceClient authServiceClient, SecurityProperties properties, ObjectMapper objectMapper) {
        FilterRegistrationBean<ApiKeyAuthenticationFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(
                new ApiKeyAuthenticationFilter(authServiceClient, properties.getApiKeyPaths(), objectMapper));
        registration.setOrder(SECURITY_FILTER_ORDER);
        registration.addUrlPatterns("/*");
        return registration;
    }

    /** Service-to-service endpoints, guarded by a secret that is not the platform admin token. */
    @Bean
    public FilterRegistrationBean<Filter> internalTokenFilter(
            SecurityProperties properties, ObjectMapper objectMapper) {
        if (properties.getInternalPaths().isEmpty()) {
            return notRegistered();
        }
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new AdminTokenFilter(
                AdminTokenFilter.INTERNAL_TOKEN_HEADER,
                "openpay.security.internal-token",
                properties.getInternalToken(),
                properties.getInternalPaths(),
                objectMapper));
        registration.setOrder(SECURITY_FILTER_ORDER + 2);
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Bean
    public FilterRegistrationBean<AdminTokenFilter> adminTokenFilter(
            SecurityProperties properties, ObjectMapper objectMapper) {
        FilterRegistrationBean<AdminTokenFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(
                new AdminTokenFilter(properties.getAdminToken(), properties.getAdminPaths(), objectMapper));
        registration.setOrder(SECURITY_FILTER_ORDER + 1);
        registration.addUrlPatterns("/*");
        return registration;
    }

    /**
     * Operator reporting and administration that does not mint a credential — see the tier
     * breakdown on {@link AdminTokenFilter}. Guarded by its own secret so it is not the admin
     * token that ends up embedded in a reporting dashboard or a cron job.
     */
    @Bean
    public FilterRegistrationBean<Filter> opsTokenFilter(SecurityProperties properties, ObjectMapper objectMapper) {
        if (properties.getOpsPaths().isEmpty()) {
            return notRegistered();
        }
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new AdminTokenFilter(
                AdminTokenFilter.OPS_TOKEN_HEADER,
                "openpay.security.ops-token",
                properties.getOpsToken(),
                properties.getOpsPaths(),
                objectMapper));
        registration.setOrder(SECURITY_FILTER_ORDER + 3);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
