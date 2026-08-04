package com.openpay.vault.domain;

/**
 * Which network a card belongs to, worked out from its leading digits.
 *
 * <p>The ranges below are the issuer identifier ranges those networks actually publish. They are
 * deliberately not exhaustive — JCB, UnionPay, Maestro and several others exist — because a network
 * this platform cannot route to is better refused at the door than accepted and failed at an
 * acquirer, where the customer has already committed. {@link #UNKNOWN} is the honest answer and the
 * caller turns it into a refusal.
 *
 * <p>Length is checked per network rather than globally: Amex is 15 digits, Diners can be 14, and a
 * single "13 to 19" rule would accept a 16-digit Amex that no acquirer will ever authorise.
 */
public enum CardNetwork {

    VISA,
    MASTERCARD,
    AMEX,
    RUPAY,
    DINERS,
    UNKNOWN;

    /** The lowercase name the rest of the platform uses, matching PaymentMethodRequest's pattern. */
    public String wireName() {
        return name().toLowerCase();
    }

    /**
     * @param digits the card number with every separator already stripped
     */
    public static CardNetwork detect(String digits) {
        if (digits == null || digits.length() < 12) {
            return UNKNOWN;
        }
        int first2 = prefix(digits, 2);
        int first3 = prefix(digits, 3);
        int first4 = prefix(digits, 4);

        // RuPay before Visa and Mastercard, because it is the one that overlaps: RuPay issues in
        // 60/65/81/82 and 508, which do not collide here, but the ordering is what stops a future
        // range edit from being silently shadowed by the broader rules below.
        if (first2 == 60 || first2 == 65 || first2 == 81 || first2 == 82 || first3 == 508) {
            return digits.length() == 16 ? RUPAY : UNKNOWN;
        }
        if (digits.charAt(0) == '4') {
            return digits.length() == 13 || digits.length() == 16 || digits.length() == 19 ? VISA : UNKNOWN;
        }
        if ((first2 >= 51 && first2 <= 55) || (first4 >= 2221 && first4 <= 2720)) {
            return digits.length() == 16 ? MASTERCARD : UNKNOWN;
        }
        if (first2 == 34 || first2 == 37) {
            return digits.length() == 15 ? AMEX : UNKNOWN;
        }
        if (first2 == 36 || first2 == 38 || (first3 >= 300 && first3 <= 305)) {
            return digits.length() == 14 || digits.length() == 16 ? DINERS : UNKNOWN;
        }
        return UNKNOWN;
    }

    /** How many digits this network's security code has. Amex is the odd one out, as always. */
    public int securityCodeLength() {
        return this == AMEX ? 4 : 3;
    }

    private static int prefix(String digits, int length) {
        return Integer.parseInt(digits.substring(0, length));
    }
}
