package com.openpay.payment.infrastructure;

/**
 * The token supplied with a payment could not be spent.
 *
 * <p>One exception for unknown, expired, already-spent and wrong-merchant, carrying one message, on
 * purpose: distinguishing them would tell a caller which tokens have existed and which have already
 * been used.
 */
public class TokenNotRedeemableException extends RuntimeException {

    public TokenNotRedeemableException(String message) {
        super(message);
    }
}
