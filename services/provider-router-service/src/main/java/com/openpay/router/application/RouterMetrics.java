package com.openpay.router.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * What the acquirers are actually doing.
 *
 * <p>These answer the question the dashboards exist for: is a payment failing because an acquirer
 * refused it, or because we never offered it to one? Those look identical from the outside and want
 * completely different people woken up.
 *
 * <p>Counters only, and no dependency on {@link RoutingService} — the routing service depends on
 * this, and pointing them at each other would be a circular reference the context refuses to start
 * with. Breaker state is sampled separately by {@link BreakerStateMetrics}.
 */
@Component
public class RouterMetrics {

    private final MeterRegistry meterRegistry;

    public RouterMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void attempt(String provider, String outcome) {
        Counter.builder("openpay.provider.attempts")
                .description("Dispatch attempts against an acquirer")
                .tag("provider", provider)
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
    }

    public void skippedByBreaker(String provider) {
        Counter.builder("openpay.provider.skipped")
                .description("Acquirers passed over because their circuit breaker was open")
                .tag("provider", provider)
                .register(meterRegistry)
                .increment();
    }

    public void routingExhausted(String reason) {
        Counter.builder("openpay.routing.exhausted")
                .description("Payments that could not be routed to any acquirer")
                // A short, closed set of reasons rather than the message text, which would be
                // unbounded and would make the series useless the first time one was reworded.
                .tag("reason", reason)
                .register(meterRegistry)
                .increment();
    }
}
