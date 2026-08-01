package com.openpay.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * What the customer paid with, reduced to what is safe to keep.
 *
 * <p>A merchant sends an instrument token when it creates a payment. That token is used to reach
 * the acquirer and is then dropped: storing it would make this table worth stealing, and nothing
 * downstream needs it. What survives is the part a human uses to recognise a payment — a card
 * network and its last four digits, or a UPI handle with its local part masked.
 */
@Embeddable
public class PaymentMethod {

    @Column(name = "payment_method_type", length = 20)
    private String type;

    @Column(name = "payment_method_network", length = 20)
    private String network;

    @Column(name = "payment_method_last4", length = 4, columnDefinition = "bpchar")
    private String last4;

    @Column(name = "payment_method_vpa", length = 120)
    private String vpa;

    @Column(name = "payment_method_bank", length = 60)
    private String bank;

    protected PaymentMethod() {
        // JPA only
    }

    public PaymentMethod(String type, String network, String last4, String vpa, String bank) {
        this.type = normalise(type);
        this.network = normalise(network);
        this.last4 = blankToNull(last4);
        this.vpa = maskVpa(vpa);
        this.bank = blankToNull(bank);
    }

    /**
     * A VPA is a person's identifier, so only enough of it survives to tell two of them apart.
     * The handle after the @ is kept whole: it names the bank, not the customer.
     */
    static String maskVpa(String vpa) {
        String trimmed = blankToNull(vpa);
        if (trimmed == null) {
            return null;
        }
        int at = trimmed.indexOf('@');
        if (at <= 0) {
            return trimmed;
        }
        String local = trimmed.substring(0, at);
        String handle = trimmed.substring(at);
        String head = local.length() <= 2 ? local.substring(0, 1) : local.substring(0, 2);
        return head + "***" + handle;
    }

    private static String normalise(String value) {
        String trimmed = blankToNull(value);
        return trimmed == null ? null : trimmed.toLowerCase();
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** True when nothing usable was supplied, so the payment stores no method at all. */
    public boolean isEmpty() {
        return type == null && network == null && last4 == null && vpa == null && bank == null;
    }

    public String getType() {
        return type;
    }

    public String getNetwork() {
        return network;
    }

    public String getLast4() {
        return last4;
    }

    public String getVpa() {
        return vpa;
    }

    public String getBank() {
        return bank;
    }
}
