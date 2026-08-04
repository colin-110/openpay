package com.openpay.fraud.application;

import com.openpay.fraud.domain.FraudDecisionRepository;
import com.openpay.fraud.domain.FraudRuleRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the screening read path at startup and then keeps running it, so that no real payment is
 * ever the first thing through a cold path.
 *
 * <p>This is not a micro-optimisation. Screening is a synchronous call inside the merchant's
 * request with a 1-second read timeout, and it <em>fails open</em> — so a first request slow enough
 * to time out does not produce an error, it produces a payment recorded {@code UNSCREENED}. Nobody
 * looked at it, and it went through.
 *
 * <p>Measured, repeatedly: the first payment through a freshly started stack came back
 * {@code UNSCREENED}, and every payment after it screened in about 37ms. The cost is entirely
 * first-call — class loading, Hibernate's first query plan, the connection handshake — and it
 * exceeded the caller's timeout on its own. Raising {@code minimum-idle} was not enough, because
 * the pool was never the slow part.
 *
 * <h2>Why it repeats, and does not just run once</h2>
 *
 * <p>Running this only at startup fixed the cold-start case and left a larger one open: the path
 * goes cold again whenever it is simply <em>unused</em>. Taken from the payments table of a stack
 * that had been up for three hours, every single unscreened payment followed an idle gap, and every
 * payment made while traffic was flowing screened normally:
 *
 * <pre>
 *   33s after start   UNSCREENED     (cold start — what the startup run was added for)
 *   40s later         ALLOWED
 *   2m 24s idle       UNSCREENED
 *   seconds apart     ALLOWED  x6
 *   3h 02m idle       UNSCREENED
 * </pre>
 *
 * <p>Two and a half minutes of quiet was enough. That makes the startup-only version close to
 * useless for the deployment that needs it most: a platform nobody is using is idle essentially
 * all of the time, so <em>every</em> payment it takes is a first-payment-after-idle, and every one
 * of them skips the risk rules. The failure is also completely silent — a 201, a captured payment,
 * and one field reading {@code UNSCREENED} that nothing alerts on.
 *
 * <p>So the same work runs on a timer. The interval is shorter than the things that go cold on
 * their own — HikariCP's 60s {@code idle-timeout}, and the 30s idle eviction on the calling
 * service's HTTP connection pool — which is what keeps the path continuously warm rather than
 * merely warm shortly after boot. Two indexed queries every half minute is not a load; it is
 * cheaper than the single payment it stops going unscreened.
 *
 * <p>Reads only. Warming {@link FraudService#screen} itself would be a better simulation and would
 * also write a decision row for a payment that does not exist, so the queries are exercised
 * directly instead: the same tables, the same Hibernate machinery, no invented history.
 */
@Component
public class ScreeningWarmUp implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ScreeningWarmUp.class);

    private final FraudRuleRepository ruleRepository;
    private final FraudDecisionRepository decisionRepository;
    private final boolean enabled;

    public ScreeningWarmUp(
            FraudRuleRepository ruleRepository,
            FraudDecisionRepository decisionRepository,
            @Value("${openpay.fraud.warm-up:true}") boolean enabled) {
        this.ruleRepository = ruleRepository;
        this.decisionRepository = decisionRepository;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        long elapsedMs = warmUp();
        if (elapsedMs >= 0) {
            log.info("Screening warm-up completed in {}ms; the first real payment will not pay for it",
                    elapsedMs);
        }
    }

    /**
     * Keeps the path warm between payments.
     *
     * <p>Logged at debug: this runs on a timer forever, and at info it would be the only thing in
     * the log of an idle service. The startup run above is the one worth announcing.
     */
    @Scheduled(
            fixedDelayString = "${openpay.fraud.warm-up-interval-ms:30000}",
            initialDelayString = "${openpay.fraud.warm-up-interval-ms:30000}")
    public void keepWarm() {
        if (!enabled) {
            return;
        }
        long elapsedMs = warmUp();
        if (elapsedMs >= 0) {
            log.debug("Screening path kept warm in {}ms", elapsedMs);
        }
    }

    /** @return elapsed milliseconds, or -1 if the warm-up failed. */
    private long warmUp() {
        try {
            long startedAt = System.nanoTime();
            // The two queries every screening makes: which rules are live, and has this payment
            // already been decided. A UUID that cannot exist, so the second is a real index probe
            // that returns nothing.
            ruleRepository.findByEnabledTrueOrderByPriorityAsc();
            decisionRepository.findByPaymentId(UUID.randomUUID());
            return (System.nanoTime() - startedAt) / 1_000_000;
        } catch (RuntimeException exception) {
            // Never fatal, at startup or on the timer. A service that refuses to start because it
            // could not warm itself up is strictly worse than one that starts cold — the cold one
            // still screens, just slowly — and a failed sweep must not kill the schedule, because
            // an unreachable database is exactly when the next sweep matters most.
            log.warn("Screening warm-up failed; the next payment may be slow enough to fail open",
                    exception);
            return -1;
        }
    }
}
