package com.openpay.observability;

import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

@AutoConfiguration
public class ObservabilityAutoConfiguration {

    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new CorrelationIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }

    /** See {@link MetricsConventions#httpLatencyHistograms()} for why buckets and not percentiles. */
    @Bean
    public MeterFilter httpLatencyHistograms() {
        return MetricsConventions.httpLatencyHistograms();
    }

    @Bean
    public MeterFilter uriCardinalityCeiling(
            @Value("${openpay.metrics.max-uri-series:100}") int maxUriSeries) {
        return MetricsConventions.cardinalityCeiling(maxUriSeries);
    }
}
