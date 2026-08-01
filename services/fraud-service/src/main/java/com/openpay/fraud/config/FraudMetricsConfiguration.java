package com.openpay.fraud.config;

import com.openpay.fraud.application.FraudService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FraudMetricsConfiguration {

    /**
     * The depth of the review queue.
     *
     * <p>A gauge rather than a counter because the question worth alerting on is "how many payments
     * are waiting right now", not "how many have ever waited". A queue that grows without being
     * worked is a checkout full of customers being told nothing.
     */
    @Bean
    public Gauge openReviewsGauge(MeterRegistry registry, FraudService fraudService) {
        return Gauge.builder("openpay_fraud_open_reviews", fraudService, FraudService::openReviewCount)
                .description("Payments held for manual review and not yet resolved")
                .register(registry);
    }
}
