package com.openpay.router.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class CircuitBreakerTest {

    @Test
    void startsClosedAndAllowsTraffic() {
        CircuitBreaker breaker = new CircuitBreaker("bank", 3, Duration.ofSeconds(30));

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.allowsRequest()).isTrue();
    }

    @Test
    void staysClosedBelowTheThreshold() {
        CircuitBreaker breaker = new CircuitBreaker("bank", 3, Duration.ofSeconds(30));

        breaker.recordFailure();
        breaker.recordFailure();

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.allowsRequest()).isTrue();
    }

    @Test
    void opensOnTheThresholdAndStopsTraffic() {
        CircuitBreaker breaker = new CircuitBreaker("bank", 3, Duration.ofSeconds(30));

        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordFailure();

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        // The point of opening: later payments skip this provider instead of each waiting out a
        // timeout to learn what we already know.
        assertThat(breaker.allowsRequest()).isFalse();
    }

    @Test
    void aSuccessResetsTheFailureRun() {
        CircuitBreaker breaker = new CircuitBreaker("bank", 3, Duration.ofSeconds(30));

        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordSuccess();
        breaker.recordFailure();
        breaker.recordFailure();

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void halfOpensAfterTheWindowAndLetsAProbeThrough() throws InterruptedException {
        CircuitBreaker breaker = new CircuitBreaker("bank", 1, Duration.ofMillis(50));
        breaker.recordFailure();
        assertThat(breaker.allowsRequest()).isFalse();

        Thread.sleep(80);

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        assertThat(breaker.allowsRequest()).isTrue();
    }

    @Test
    void aFailedProbeReopensTheBreaker() throws InterruptedException {
        CircuitBreaker breaker = new CircuitBreaker("bank", 1, Duration.ofMillis(50));
        breaker.recordFailure();
        Thread.sleep(80);
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        breaker.recordFailure();

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void aSuccessfulProbeClosesTheBreaker() throws InterruptedException {
        CircuitBreaker breaker = new CircuitBreaker("bank", 1, Duration.ofMillis(50));
        breaker.recordFailure();
        Thread.sleep(80);

        breaker.recordSuccess();

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
