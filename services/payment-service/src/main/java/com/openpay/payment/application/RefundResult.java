package com.openpay.payment.application;

import com.openpay.payment.api.RefundResponse;

/** Carries whether this call created the refund, so a replay can answer 200 instead of 201. */
public record RefundResult(RefundResponse refund, boolean created) {
}
