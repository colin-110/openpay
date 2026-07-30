package com.openpay.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.openpay.gateway.routing.ReverseProxy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class GatewayServiceApplicationTests {

    @Autowired
    private ReverseProxy reverseProxy;

    @Test
    void routesAreConfiguredForBothDownstreamServices() {
        assertThat(reverseProxy.routeFor("/api/v1/payments/abc")).isPresent();
        assertThat(reverseProxy.routeFor("/api/v1/merchants")).isPresent();
        assertThat(reverseProxy.routeFor("/api/v1/ping")).isEmpty();
    }
}
