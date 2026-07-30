package com.openpay.auth.config;

import com.openpay.auth.infrastructure.MerchantServiceClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class AuthClientConfiguration {

    @Bean
    public MerchantServiceClient merchantServiceClient(
            @Value("${openpay.merchant.base-url}") String merchantBaseUrl,
            @Value("${openpay.security.admin-token:}") String adminToken) {
        // Bounded timeouts: an unresponsive merchant-service must not pin request threads.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(2000);
        requestFactory.setReadTimeout(3000);

        RestClient restClient = RestClient.builder()
                .baseUrl(merchantBaseUrl)
                .requestFactory(requestFactory)
                .build();
        return new MerchantServiceClient(restClient, adminToken);
    }
}
