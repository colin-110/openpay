package com.openpay.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class GatewayClientConfiguration {

    @Bean
    public RestClient authRestClient(RestClient.Builder builder, @Value("${openpay.auth.base-url}") String authBaseUrl) {
        return builder.baseUrl(authBaseUrl).build();
    }
}
