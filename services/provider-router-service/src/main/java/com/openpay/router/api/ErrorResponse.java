package com.openpay.router.api;

import java.time.OffsetDateTime;

public record ErrorResponse(String code, String message, String path, OffsetDateTime timestamp) {
}
