package com.openpay.fraud.application;

import java.time.Duration;
import java.util.UUID;

/**
 * How the engine counts recent traffic.
 *
 * <p>An interface rather than a repository call, so the rule logic can be tested against exact
 * counts instead of against a database seeded with enough rows to trip a threshold. The production
 * implementation is two indexed counts over {@code fraud_decisions}.
 */
public interface VelocitySource {

    /** How many payments this merchant has had screened within the window. */
    long countForMerchant(UUID merchantId, Duration window);

    /** How many of them were for exactly this amount. */
    long countForMerchantAndAmount(UUID merchantId, long amount, Duration window);
}
