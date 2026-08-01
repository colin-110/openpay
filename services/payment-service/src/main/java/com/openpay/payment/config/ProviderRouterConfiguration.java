package com.openpay.payment.config;

import com.openpay.payment.infrastructure.ProviderRouterClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
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
            @Value("${openpay.router.base-url:http://localhost:8085}") String baseUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) Duration.ofSeconds(1).toMillis());
        requestFactory.setReadTimeout((int) Duration.ofSeconds(2).toMillis());

        return new ProviderRouterClient(
                RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build());
    }
}
