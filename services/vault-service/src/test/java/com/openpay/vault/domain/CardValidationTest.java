package com.openpay.vault.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The two checks that decide whether a card number is worth sending to a bank at all.
 *
 * <p>Every number below is a published test number or a constructed one — none is a real card, and
 * none could be: they are exactly the numbers that pass Luhn and belong to no issuer.
 */
class CardValidationTest {

    @ParameterizedTest
    @CsvSource({
            "4242424242424242, VISA",
            "4000056655665556, VISA",
            "5555555555554444, MASTERCARD",
            // The 2221-2720 range Mastercard added in 2017. Easy to miss, and a card that would be
            // refused as an unknown network is a customer who cannot pay for no reason.
            "2223003122003222, MASTERCARD",
            "378282246310005,  AMEX",
            "371449635398431,  AMEX",
            "6521123456789012, RUPAY",
            "36227206271667,   DINERS",
    })
    void detectsTheNetworkFromTheLeadingDigits(String number, CardNetwork expected) {
        assertThat(CardNetwork.detect(number)).isEqualTo(expected);
    }

    @Test
    void refusesARightPrefixWithTheWrongLength() {
        // A 16-digit Amex passes Luhn happily and no acquirer will ever authorise it. Length is
        // checked per network for exactly this case.
        assertThat(CardNetwork.detect("3782822463100051")).isEqualTo(CardNetwork.UNKNOWN);
        assertThat(CardNetwork.detect("55555555555544")).isEqualTo(CardNetwork.UNKNOWN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"9999999999999999", "1234567890123456", ""})
    void refusesNetworksThisPlatformCannotRoute(String number) {
        assertThat(CardNetwork.detect(number)).isEqualTo(CardNetwork.UNKNOWN);
    }

    @Test
    void amexAsksForFourSecurityDigitsAndEveryoneElseForThree() {
        assertThat(CardNetwork.AMEX.securityCodeLength()).isEqualTo(4);
        assertThat(CardNetwork.VISA.securityCodeLength()).isEqualTo(3);
        assertThat(CardNetwork.RUPAY.securityCodeLength()).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "4242424242424242", "5555555555554444", "378282246310005", "36227206271667", "6521123456789012"})
    void acceptsNumbersWhoseCheckDigitAgrees(String number) {
        assertThat(Luhn.isValid(number)).isTrue();
    }

    @Test
    void catchesTheSingleMistypedDigit() {
        // The failure Luhn exists for, and by far the most common thing wrong with a card number.
        assertThat(Luhn.isValid("4242424242424241")).isFalse();
    }

    @Test
    void catchesTheTransposedPair() {
        // 4242... with the first two digits swapped. Luhn catches every adjacent transposition
        // except 09 <-> 90, which is the reason a plain digit sum would not do: a plain sum is
        // unchanged by any reordering at all.
        assertThat(Luhn.isValid("4242424242424242")).isTrue();
        assertThat(Luhn.isValid("2442424242424242")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"4242 4242 4242 4242", "424242424242424a", "", "42424242424"})
    void refusesAnythingThatIsNotADigitStringOfAPlausibleLength(String number) {
        // Separators included on purpose: stripping them is the caller's job, done before this is
        // reached, so that this function has exactly one responsibility.
        assertThat(Luhn.isValid(number)).isFalse();
    }
}
