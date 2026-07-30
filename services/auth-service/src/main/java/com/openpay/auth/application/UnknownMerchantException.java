package com.openpay.auth.application;

import java.util.UUID;

public class UnknownMerchantException extends RuntimeException {

    public UnknownMerchantException(UUID merchantId) {
        super("Merchant does not exist: " + merchantId);
    }
}
