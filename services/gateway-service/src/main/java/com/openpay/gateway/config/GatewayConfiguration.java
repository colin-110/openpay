package com.openpay.gateway.config;

import com.openpay.gateway.routing.ReverseProxy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.openpay.security.InternalHttpClients;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayConfiguration {

    @Bean
    public ReverseProxy reverseProxy(GatewayProperties properties) {
        // Bounded timeouts: without them a hung downstream service holds a gateway thread forever
        // and the whole gateway degrades with it.
        // The hottest client on the platform: every proxied request goes through it. 200 per
        // route because the gateway fans out to several services and each is its own route.
        RestClient restClient = RestClient.builder()
                .requestFactory(InternalHttpClients.pooled(
                        properties.getConnectTimeout(), properties.getReadTimeout(), 200))
                .build();
        return new ReverseProxy(restClient, properties.getRoutes());
    }
}
