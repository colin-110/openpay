package com.openpay.vault.domain;

/**
 * The check digit every card number carries.
 *
 * <p>Worth doing here, at the edge, rather than discovering it at an acquirer: a mistyped digit is
 * by far the most common thing wrong with a card number, and catching it costs microseconds while
 * the alternative costs a round trip to a bank and gives the customer a decline that looks like
 * their card was refused rather than mistyped.
 *
 * <p>It is a checksum, not an authorisation. A number that passes Luhn is well-formed; it says
 * nothing about whether the card exists, has funds, or has been reported stolen. Only the acquirer
 * knows that, and this check does not pretend otherwise.
 */
public final class Luhn {

    private Luhn() {
    }

    /**
     * @param digits the card number, digits only
     */
    public static boolean isValid(String digits) {
        if (digits == null || digits.length() < 12 || digits.length() > 19) {
            return false;
        }
        int sum = 0;
        // Right to left: every second digit is doubled, and a double that goes past 9 has its own
        // digits added — which is the same as subtracting 9, and cheaper than converting to a
        // string to add them.
        boolean doubling = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            char character = digits.charAt(i);
            if (character < '0' || character > '9') {
                return false;
            }
            int digit = character - '0';
            if (doubling) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubling = !doubling;
        }
        return sum % 10 == 0;
    }
}
