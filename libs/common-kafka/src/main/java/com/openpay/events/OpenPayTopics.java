package com.openpay.events;

/**
 * Canonical topic names.
 *
 * <p>The {@code .v1} suffix is the schema version. A breaking change to a payload publishes to a
 * new topic rather than mutating the old one, so consumers can migrate on their own schedule.
 */
public final class OpenPayTopics {

    /** A payment was accepted and needs routing to a provider. Keyed by payment id. */
    public static final String PAYMENT_CREATED = "payment.created.v1";

    /** A payment was handed to a specific provider. Keyed by payment id. */
    public static final String PAYMENT_PROVIDER_DISPATCHED = "payment.provider-dispatched.v1";

    /** A payment moved between states. Keyed by payment id. */
    public static final String PAYMENT_STATUS_UPDATED = "payment.status-updated.v1";

    /** A provider told us the outcome of a transaction. Keyed by payment id. */
    public static final String PROVIDER_CALLBACK_RECEIVED = "provider.callback-received.v1";

    private OpenPayTopics() {
    }
}
