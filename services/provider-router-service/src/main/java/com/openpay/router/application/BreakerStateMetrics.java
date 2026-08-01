package com.openpay.router.application;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.util.Map;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Which acquirers are currently out of rotation.
 *
 * <p>A {@link MultiGauge} resampled on a schedule, rather than one gauge per provider registered at
 * startup. Providers come from a table now, so the set changes while the service is running: a
 * gauge bound at boot would keep reporting an acquirer that is no longer routed, and would never
 * report one added since.
 *
 * <p>Separate from {@link RouterMetrics} because this needs {@link RoutingService} and
 * {@code RoutingService} needs {@code RouterMetrics}. Putting both in one class would be a circular
 * reference, which the context refuses to start with — correctly.
 */
@Component
public class BreakerStateMetrics {

    private final RoutingService routingService;
    private final MultiGauge breakerStates;

    public BreakerStateMetrics(MeterRegistry meterRegistry, RoutingService routingService) {
        this.routingService = routingService;
        this.breakerStates = MultiGauge.builder("openpay.provider.breaker.open")
                .description("1 when an acquirer's circuit breaker is open, 0 when it is not")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${openpay.router.metrics-interval:PT10S}")
    public void sample() {
        Map<String, CircuitBreaker.State> states = routingService.breakerStates();
        breakerStates.register(states.entrySet().stream()
                .map(entry -> MultiGauge.Row.of(
                        Tags.of("provider", entry.getKey()),
                        entry.getValue() == CircuitBreaker.State.OPEN ? 1 : 0))
                .toList(), true);
    }
}
