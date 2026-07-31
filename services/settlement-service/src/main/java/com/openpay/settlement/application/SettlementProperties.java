package com.openpay.settlement.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "openpay.settlement")
public class SettlementProperties {

    /** Percentage fee in basis points. 200 = 2.00%. */
    private int feeBasisPoints = 200;

    /**
     * Flat fee per payment, in minor units. Zero by default: with a non-zero flat fee a small
     * enough payment nets negative, which is a real situation but needs a carry-forward policy
     * this phase does not implement.
     */
    private long feeFixedMinor = 0;

    /**
     * How long a captured payment waits before it is eligible for payout. Real acquirers reserve
     * the right to reverse recent transactions, so settling instantly means paying out money that
     * can still be taken back.
     */
    private Duration holdPeriod = Duration.ZERO;

    public int getFeeBasisPoints() {
        return feeBasisPoints;
    }

    public void setFeeBasisPoints(int feeBasisPoints) {
        this.feeBasisPoints = feeBasisPoints;
    }

    public long getFeeFixedMinor() {
        return feeFixedMinor;
    }

    public void setFeeFixedMinor(long feeFixedMinor) {
        this.feeFixedMinor = feeFixedMinor;
    }

    public Duration getHoldPeriod() {
        return holdPeriod;
    }

    public void setHoldPeriod(Duration holdPeriod) {
        this.holdPeriod = holdPeriod;
    }
}
