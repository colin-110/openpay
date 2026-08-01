package com.openpay.payment.application;

import com.openpay.payment.domain.FraudStatus;
import com.openpay.payment.domain.PaymentStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * The business side of the RED metrics.
 *
 * <p>Spring Boot already exports rate, errors, and duration per HTTP endpoint, which answers "is
 * the API healthy". It cannot answer "are payments completing", because a payment that is accepted
 * and then never captured is two successful HTTP requests and one stuck customer. These count the
 * lifecycle instead of the requests.
 *
 * <p>Every tag here is drawn from a closed set — a status, a currency code, a fraud outcome. None
 * of them is a merchant id or a payment id: a label whose cardinality grows with traffic turns a
 * time series database into an outage.
 *
 * <p>Two naming traps, both found by scraping the real endpoint rather than by reading the docs.
 *
 * <p>Names are dotted and carry no {@code _total} suffix: Micrometer's Prometheus registry appends
 * it, so writing the Prometheus name here produces {@code ..._total_total} and matches nothing a
 * dashboard queries.
 *
 * <p>And a counter must not end in {@code created}. {@code _created} is a reserved OpenMetrics
 * suffix — it marks a counter's creation timestamp — so the client strips it: this counter was
 * {@code openpay.payments.created} and reached Prometheus as {@code openpay_payments_total}, having
 * quietly lost the word that said what it counted.
 */
@Component
public class PaymentMetrics {

    private final MeterRegistry meterRegistry;

    public PaymentMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void paymentCreated(String currency, FraudStatus fraudStatus) {
        Counter.builder("openpay.payments.accepted")
                .description("Payments accepted, by currency and screening outcome")
                .tag("currency", currency == null ? "unknown" : currency.toUpperCase())
                .tag("screening", fraudStatus == null ? "unknown" : fraudStatus.name())
                .register(meterRegistry)
                .increment();
    }

    public void paymentBlocked(String ruleName) {
        Counter.builder("openpay.payments.blocked")
                .description("Payments refused by risk screening before anything was persisted")
                // The rule name, which is bounded by the size of the rules table.
                .tag("rule", ruleName == null ? "unknown" : ruleName)
                .register(meterRegistry)
                .increment();
    }

    /**
     * A lifecycle transition.
     *
     * <p>Both ends are tagged because the interesting rates are transitions, not states:
     * {@code PENDING_PROVIDER -> FAILED} climbing is an acquirer problem, while
     * {@code CREATED -> FAILED} climbing is a routing problem, and a single "failed" counter cannot
     * tell them apart.
     */
    public void transition(PaymentStatus from, PaymentStatus to) {
        Counter.builder("openpay.payment.transitions")
                .description("Payment lifecycle transitions")
                .tag("from", from.name())
                .tag("to", to.name())
                .register(meterRegistry)
                .increment();
    }
}
