package com.openpay.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

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

    @Bean
    @ConditionalOnMissingBean(AuthServiceClient.class)
    public AuthServiceClient authServiceClient(SecurityProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) properties.getConnectTimeout().toMillis());
        requestFactory.setReadTimeout((int) properties.getReadTimeout().toMillis());

        RestClient restClient = RestClient.builder()
                .baseUrl(properties.getAuthBaseUrl())
                .requestFactory(requestFactory)
                .build();
        return new HttpAuthServiceClient(restClient);
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
}
