package com.openpay.router;

import com.openpay.router.application.RouterProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

// Scheduling is for BreakerStateMetrics, which resamples which acquirers are out of rotation.
// This service has no outbox, so nothing else was switching it on.
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(RouterProperties.class)
public class ProviderRouterApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProviderRouterApplication.class, args);
    }
}
