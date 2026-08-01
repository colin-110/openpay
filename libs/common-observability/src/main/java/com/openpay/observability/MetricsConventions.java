package com.openpay.observability;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import java.time.Duration;

/**
 * The metric conventions every service shares.
 *
 * <p>A {@link MeterFilter} rather than a block of YAML copied into eleven {@code application.yml}
 * files. Conventions that live in configuration drift: a service added later gets whatever its
 * author remembered, and the dashboards silently have a gap for it.
 */
public final class MetricsConventions {

    /** Anything slower than this is equally bad, so there is no value in buckets beyond it. */
    private static final Duration SLOWEST_MEANINGFUL = Duration.ofSeconds(10);

    /**
     * Publishes histogram buckets for HTTP request timings.
     *
     * <p>Spring Boot exports count, sum, and max by default, which is enough for an average and
     * nothing else. An average latency is close to useless here: it hides exactly the tail that a
     * customer waiting at a checkout actually experiences, and the p99 is the number that says
     * whether the platform is behaving.
     *
     * <p>Buckets rather than client-side percentiles, because buckets can be aggregated across
     * instances and percentiles cannot — averaging two instances' p99s produces a number that is
     * not any instance's p99 and not the fleet's either.
     */
    public static MeterFilter httpLatencyHistograms() {
        return new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                if (!id.getName().equals("http.server.requests")) {
                    return config;
                }
                return DistributionStatisticConfig.builder()
                        .percentilesHistogram(true)
                        .minimumExpectedValue(Duration.ofMillis(5).toNanos() * 1.0)
                        .maximumExpectedValue((double) SLOWEST_MEANINGFUL.toNanos())
                        .build()
                        .merge(config);
            }
        };
    }

    /**
     * Caps how many series one metric can create.
     *
     * <p>A backstop, not a policy. Every tag in this codebase is drawn from a closed set on
     * purpose, but the failure mode when one is not — a merchant id or a payment id reaching a
     * label — is that Prometheus runs out of memory rather than that a dashboard looks odd. This
     * makes that mistake lose a metric instead of a monitoring stack.
     */
    public static MeterFilter cardinalityCeiling(int maximumSeries) {
        return MeterFilter.maximumAllowableTags(
                "http.server.requests", "uri", maximumSeries, MeterFilter.deny());
    }

    private MetricsConventions() {
    }
}
