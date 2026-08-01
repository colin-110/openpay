package com.openpay.merchant.domain;

/** The webhook URL is well formed but the platform will not send to it. */
public class UndeliverableWebhookUrlException extends RuntimeException {

    public UndeliverableWebhookUrlException(String problem) {
        super("webhookUrl " + problem);
    }
}
