package com.openpay.fraud.api;

import java.time.OffsetDateTime;

public record ErrorResponse(String code, String message, String path, OffsetDateTime timestamp) {
}
