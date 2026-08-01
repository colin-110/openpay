package com.openpay.payment.infrastructure;

/** Screening could not be reached and this deployment has chosen to fail closed. */
public class ScreeningUnavailableException extends RuntimeException {

    public ScreeningUnavailableException(String detail) {
        super("Risk screening is unavailable and this deployment refuses unscreened payments: " + detail);
    }
}
