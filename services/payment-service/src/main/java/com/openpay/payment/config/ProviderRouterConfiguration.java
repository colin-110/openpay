package com.openpay.payment.config;

import com.openpay.payment.infrastructure.ProviderRouterClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.openpay.security.InternalHttpClients;
import org.springframework.web.client.RestClient;

@Configuration
public class ProviderRouterConfiguration {

    /**
     * Short timeouts on purpose. This call sits behind a merchant opening one payment, and a slow
     * router should cost that panel a second, not hold the request open.
     */
    @Bean
    @ConditionalOnMissingBean(ProviderRouterClient.class)
    public ProviderRouterClient providerRouterClient(
            @Value("${openpay.router.base-url:http://localhost:8085}") String baseUrl,
            @Value("${openpay.security.internal-token:}") String internalToken) {
        return new ProviderRouterClient(
                RestClient.builder()
                        .baseUrl(baseUrl)
                        .requestFactory(InternalHttpClients.pooled(
                                Duration.ofSeconds(1), Duration.ofSeconds(2), 50))
                        .build(),
                internalToken);
    }
}
