package com.openpay.auth.api;

import com.openpay.auth.application.InvalidApiKeyException;
import com.openpay.auth.application.InvalidApiKeyRequestException;
import com.openpay.auth.application.MerchantLookupUnavailableException;
import com.openpay.auth.application.TooManyAttemptsException;
import com.openpay.auth.application.UnknownMerchantException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(InvalidApiKeyException.class)
    public ResponseEntity<ErrorResponse> handleInvalidApiKey(
            InvalidApiKeyException exception, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, "invalid_api_key", exception.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(TooManyAttemptsException.class)
    public ResponseEntity<ErrorResponse> handleTooManyAttempts(
            TooManyAttemptsException exception, HttpServletRequest request) {
        return build(HttpStatus.TOO_MANY_REQUESTS, "too_many_attempts", exception.getMessage(),
                request.getRequestURI());
    }

    @ExceptionHandler(UnknownMerchantException.class)
    public ResponseEntity<ErrorResponse> handleUnknownMerchant(
            UnknownMerchantException exception, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "unknown_merchant", exception.getMessage(),
                request.getRequestURI());
    }

    @ExceptionHandler(InvalidApiKeyRequestException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRequest(
            InvalidApiKeyRequestException exception, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "validation_error", exception.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(MerchantLookupUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleMerchantLookupUnavailable(
            MerchantLookupUnavailableException exception, HttpServletRequest request) {
        log.error("Merchant lookup failed while issuing an API key", exception);
        return build(HttpStatus.SERVICE_UNAVAILABLE, "merchant_lookup_unavailable",
                "Merchant verification is temporarily unavailable", request.getRequestURI());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("Validation failed");
        return build(HttpStatus.BAD_REQUEST, "validation_error", message, request.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("Unhandled exception on {}", request.getRequestURI(), exception);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "An unexpected error occurred",
                request.getRequestURI());
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String code, String message, String path) {
        return ResponseEntity.status(status)
                .body(new ErrorResponse(code, message, path, OffsetDateTime.now()));
    }
}
