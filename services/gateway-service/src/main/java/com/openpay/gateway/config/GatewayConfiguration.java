package com.openpay.gateway.config;

import com.openpay.gateway.routing.ReverseProxy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayConfiguration {

    @Bean
    public ReverseProxy reverseProxy(GatewayProperties properties) {
        // Bounded timeouts: without them a hung downstream service holds a gateway thread forever
        // and the whole gateway degrades with it.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) properties.getConnectTimeout().toMillis());
        requestFactory.setReadTimeout((int) properties.getReadTimeout().toMillis());

        RestClient restClient = RestClient.builder().requestFactory(requestFactory).build();
        return new ReverseProxy(restClient, properties.getRoutes());
    }
}
