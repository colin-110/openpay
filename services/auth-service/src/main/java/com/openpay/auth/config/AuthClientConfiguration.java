package com.openpay.auth.config;

import com.openpay.auth.infrastructure.MerchantServiceClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.openpay.security.InternalHttpClients;
import org.springframework.web.client.RestClient;

@Configuration
public class AuthClientConfiguration {

    @Bean
    public MerchantServiceClient merchantServiceClient(
            @Value("${openpay.merchant.base-url}") String merchantBaseUrl,
            @Value("${openpay.security.admin-token:}") String adminToken) {
        // Bounded timeouts: an unresponsive merchant-service must not pin request threads.
        // Bounded timeouts: an unresponsive merchant-service must not pin request threads.
        RestClient restClient = RestClient.builder()
                .baseUrl(merchantBaseUrl)
                .requestFactory(InternalHttpClients.pooled(
                        java.time.Duration.ofSeconds(2), java.time.Duration.ofSeconds(3), 50))
                .build();
        return new MerchantServiceClient(restClient, adminToken);
    }
}
