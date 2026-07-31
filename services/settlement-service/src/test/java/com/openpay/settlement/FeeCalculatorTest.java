package com.openpay.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openpay.settlement.application.FeeCalculator;
import com.openpay.settlement.application.SettlementProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FeeCalculatorTest {

    @Test
    void takesTwoPercentByDefault() {
        FeeCalculator.Fee fee = calculator(200, 0).calculate(10_000);

        assertThat(fee.fee()).isEqualTo(200);
        assertThat(fee.net()).isEqualTo(9_800);
        assertThat(fee.gross()).isEqualTo(10_000);
    }

    @ParameterizedTest
    @CsvSource({
            // gross, bps, expected fee. The last two land exactly on a half unit.
            "10000, 200, 200",
            "  999, 200,  20",
            "  100, 250,   3",
            "   25, 200,   1",
    })
    void roundsHalfUpOnTheExactHalf(long gross, int bps, long expectedFee) {
        // 25 * 200 / 10000 = 0.5 exactly, which must round to 1 rather than silently to 0.
        assertThat(calculator(bps, 0).calculate(gross).fee()).isEqualTo(expectedFee);
    }

    @Test
    void grossAlwaysEqualsFeePlusNet() {
        FeeCalculator calculator = calculator(275, 30);
        for (long gross = 1; gross < 5_000; gross += 37) {
            FeeCalculator.Fee fee = calculator.calculate(gross);
            // The invariant that keeps a settlement reconcilable against the payments in it.
            assertThat(fee.fee() + fee.net()).isEqualTo(fee.gross());
        }
    }

    @Test
    void addsTheFlatFeeOnTopOfThePercentage() {
        FeeCalculator.Fee fee = calculator(200, 30).calculate(10_000);

        assertThat(fee.fee()).isEqualTo(230);
        assertThat(fee.net()).isEqualTo(9_770);
    }

    @Test
    void reportsANegativeNetRatherThanHidingIt() {
        // A payment too small to cover its flat fee. Clamping this to zero would quietly make the
        // platform absorb the shortfall and the books would stop reconciling.
        FeeCalculator.Fee fee = calculator(200, 50).calculate(10);

        assertThat(fee.net()).isNegative();
        assertThat(fee.fee() + fee.net()).isEqualTo(fee.gross());
    }

    @Test
    void refusesANonPositiveGross() {
        assertThatThrownBy(() -> calculator(200, 0).calculate(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator(200, 0).calculate(-100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void handlesAmountsLargeEnoughToStressTheArithmetic() {
        // Multiplying before dividing keeps precision, so the input must stay clear of overflow.
        FeeCalculator.Fee fee = calculator(200, 0).calculate(99_999_999_999L);

        assertThat(fee.fee()).isEqualTo(2_000_000_000L);
        assertThat(fee.fee() + fee.net()).isEqualTo(fee.gross());
    }

    private FeeCalculator calculator(int basisPoints, long fixed) {
        SettlementProperties properties = new SettlementProperties();
        properties.setFeeBasisPoints(basisPoints);
        properties.setFeeFixedMinor(fixed);
        return new FeeCalculator(properties);
    }
}
