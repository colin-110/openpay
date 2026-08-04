package com.openpay.auth.api;

import com.openpay.auth.application.InvalidApiKeyException;
import com.openpay.auth.application.InvalidCredentialsException;
import com.openpay.auth.application.InvalidRefreshTokenException;
import com.openpay.auth.application.InvalidApiKeyRequestException;
import com.openpay.auth.application.MerchantLookupUnavailableException;
import com.openpay.auth.application.TooManyAttemptsException;
import com.openpay.auth.application.UnknownMerchantException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(InvalidApiKeyException.class)
    public ResponseEntity<ErrorResponse> handleInvalidApiKey(
            InvalidApiKeyException exception, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, "invalid_api_key", exception.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(
            InvalidCredentialsException exception, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, "invalid_credentials", exception.getMessage(),
                request.getRequestURI());
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRefreshToken(
            InvalidRefreshTokenException exception, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, "invalid_refresh_token", exception.getMessage(),
                request.getRequestURI());
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
        // Spring's own MVC exceptions — unknown path, wrong method, unsupported media type — already
        // carry the right status. Without this they are all flattened to 500 by the catch-all below,
        // so a typo in a URL reads as "the platform is broken" rather than "no such endpoint", and
        // a POST to a read-only path reads the same way.
        if (exception instanceof org.springframework.web.ErrorResponse springError) {
            HttpStatusCode status = springError.getStatusCode();
            return build(status, status.is4xxClientError() ? "not_found" : "internal_error",
                    "The request could not be handled", request.getRequestURI());
        }

        // A caller that hung up mid-response is not a fault on this side, and logging it at ERROR
        // with a stack trace makes it look like one. This is what a gateway read timeout looks like
        // from here: the gateway gave up on validate-key, closed the socket, and auth-service then
        // failed to flush a response nobody was waiting for. Measured under load, these were the
        // loudest errors in the log while the actual problem — the stampede that caused the timeout
        // — was invisible. Whoever is reading this log during an incident should be looking at the
        // client that disconnected, not at this stack trace.
        if (isClientDisconnect(exception)) {
            log.warn("Client disconnected before the response to {} could be written", request.getRequestURI());
            return build(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "An unexpected error occurred",
                    request.getRequestURI());
        }

        log.error("Unhandled exception on {}", request.getRequestURI(), exception);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "An unexpected error occurred",
                request.getRequestURI());
    }

    /**
     * Whether this exception is "the client went away" rather than "this service is broken".
     *
     * <p>Matched on type where possible and on the cause chain otherwise, because the broken pipe
     * arrives as an {@link java.io.IOException} several frames below whatever Spring wrapped it in.
     */
    private boolean isClientDisconnect(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof AsyncRequestNotUsableException) {
                return true;
            }
            if (current instanceof IOException) {
                String message = current.getMessage();
                if (message != null && (message.contains("Connection reset by peer")
                        || message.contains("Broken pipe")
                        || message.contains("connection was aborted"))) {
                    return true;
                }
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    private ResponseEntity<ErrorResponse> build(HttpStatusCode status, String code, String message, String path) {
        return ResponseEntity.status(status)
                .body(new ErrorResponse(code, message, path, OffsetDateTime.now()));
    }
}
