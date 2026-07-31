package com.openpay.settlement.application;

import org.springframework.stereotype.Component;

/**
 * Works out what the platform keeps from a captured payment.
 *
 * <p>Everything is integer minor units, so the only question that matters is rounding. Fees are
 * rounded half-up on the exact half, which is symmetric rather than quietly favouring either side,
 * and the arithmetic is done before any division so no precision is lost on the way.
 */
@Component
public class FeeCalculator {

    private static final int BASIS_POINT_DIVISOR = 10_000;

    private final SettlementProperties properties;

    public FeeCalculator(SettlementProperties properties) {
        this.properties = properties;
    }

    public Fee calculate(long grossMinorUnits) {
        if (grossMinorUnits <= 0) {
            throw new IllegalArgumentException("Gross amount must be positive: " + grossMinorUnits);
        }

        long percentage = Math.floorDiv(
                grossMinorUnits * properties.getFeeBasisPoints() + BASIS_POINT_DIVISOR / 2,
                BASIS_POINT_DIVISOR);
        long fee = percentage + properties.getFeeFixedMinor();

        return new Fee(grossMinorUnits, fee, grossMinorUnits - fee);
    }

    /** @param net may be negative if a flat fee exceeds a small payment; callers must handle it. */
    public record Fee(long gross, long fee, long net) {
    }
}
