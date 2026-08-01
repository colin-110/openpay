package com.openpay.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PaymentMethodTest {

    @Test
    void masksTheCustomerHalfOfAVpaAndKeepsTheBankHalf() {
        // The handle names the bank, not the person, so it survives whole.
        assertThat(PaymentMethod.maskVpa("colinthomas@okhdfcbank")).isEqualTo("co***@okhdfcbank");
    }

    @Test
    void masksShortLocalPartsWithoutRevealingThemWhole() {
        assertThat(PaymentMethod.maskVpa("ab@ybl")).isEqualTo("a***@ybl");
        assertThat(PaymentMethod.maskVpa("a@ybl")).isEqualTo("a***@ybl");
    }

    @Test
    void leavesSomethingThatIsNotAVpaAlone() {
        assertThat(PaymentMethod.maskVpa("not-a-vpa")).isEqualTo("not-a-vpa");
        assertThat(PaymentMethod.maskVpa("@ybl")).isEqualTo("@ybl");
    }

    @Test
    void treatsBlankAsAbsent() {
        assertThat(PaymentMethod.maskVpa("   ")).isNull();
        assertThat(PaymentMethod.maskVpa(null)).isNull();
        assertThat(new PaymentMethod(" ", null, "", null, "  ").isEmpty()).isTrue();
    }

    @Test
    void normalisesTypeAndNetworkSoFiltersDoNotDependOnCasing() {
        PaymentMethod method = new PaymentMethod("CARD", "RuPay", "4321", null, "HDFC");

        assertThat(method.getType()).isEqualTo("card");
        assertThat(method.getNetwork()).isEqualTo("rupay");
        assertThat(method.getLast4()).isEqualTo("4321");
        // The bank is a display name, so its casing is left as the merchant sent it.
        assertThat(method.getBank()).isEqualTo("HDFC");
    }
}
