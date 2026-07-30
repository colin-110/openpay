package com.openpay.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.OffsetDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/**
 * Writes the same error envelope the services' {@code @RestControllerAdvice} handlers use. Filters
 * run before Spring MVC, so they cannot delegate to an exception handler.
 */
class SecurityErrorWriter {

    private final ObjectMapper objectMapper;

    SecurityErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    void write(HttpServletResponse response, HttpStatus status, String code, String message, String path)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getWriter(),
                new SecurityErrorResponse(code, message, path, OffsetDateTime.now()));
    }

    record SecurityErrorResponse(String code, String message, String path, OffsetDateTime timestamp) {
    }
}
