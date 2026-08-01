package com.openpay.payment.api;

import com.openpay.payment.application.IdempotencyKeyConflictException;
import com.openpay.payment.application.PaymentBlockedException;
import com.openpay.payment.application.PaymentNotFoundException;
import com.openpay.payment.application.RefundNotAllowedException;
import com.openpay.payment.application.RefundNotFoundException;
import com.openpay.payment.domain.InvalidPaymentTransitionException;
import com.openpay.payment.infrastructure.AttemptsUnavailableException;
import com.openpay.payment.infrastructure.ScreeningUnavailableException;
import com.openpay.security.InsufficientAuthorityException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(PaymentNotFoundException exception, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "payment_not_found", exception.getMessage(), request);
    }

    @ExceptionHandler(RefundNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleRefundNotFound(
            RefundNotFoundException exception, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "refund_not_found", exception.getMessage(), request);
    }

    @ExceptionHandler(RefundNotAllowedException.class)
    public ResponseEntity<ErrorResponse> handleRefundNotAllowed(
            RefundNotAllowedException exception, HttpServletRequest request) {
        // 422 rather than 400: the request is well formed, but the payment's state or remaining
        // refundable balance will not permit it.
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "refund_not_allowed", exception.getMessage(), request);
    }

    @ExceptionHandler(InsufficientAuthorityException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientAuthority(
            InsufficientAuthorityException exception, HttpServletRequest request) {
        // 403, not 401. The credential is valid; it simply does not carry this authority, and
        // inviting the caller to retry with the same key forever helps nobody.
        log.warn("Refused {} for a credential with authority '{}'",
                request.getRequestURI(), exception.getAuthority());
        return build(HttpStatus.FORBIDDEN, "insufficient_authority", exception.getMessage(), request);
    }

    @ExceptionHandler(AttemptsUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleAttemptsUnavailable(
            AttemptsUnavailableException exception, HttpServletRequest request) {
        // 503 rather than an empty list. "No attempts recorded" and "could not ask" are different
        // answers, and a payments console showing the first when it means the second is misleading.
        return build(HttpStatus.SERVICE_UNAVAILABLE, "attempts_unavailable", exception.getMessage(), request);
    }

    @ExceptionHandler(PaymentBlockedException.class)
    public ResponseEntity<ErrorResponse> handlePaymentBlocked(
            PaymentBlockedException exception, HttpServletRequest request) {
        // 422 rather than 403: the credential was fine and the request was well formed. This
        // particular payment is the thing being refused.
        log.info("Refused a payment on risk rule '{}'", exception.getRuleName());
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "payment_blocked", exception.getMessage(), request);
    }

    @ExceptionHandler(ScreeningUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleScreeningUnavailable(
            ScreeningUnavailableException exception, HttpServletRequest request) {
        // Only reachable when the deployment has chosen to fail closed. 503 with a retryable
        // meaning, because the payment may well be fine once screening is back.
        return build(HttpStatus.SERVICE_UNAVAILABLE, "screening_unavailable", exception.getMessage(), request);
    }

    @ExceptionHandler(IdempotencyKeyConflictException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyConflict(
            IdempotencyKeyConflictException exception, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "idempotency_key_reused", exception.getMessage(), request);
    }

    @ExceptionHandler(InvalidPaymentTransitionException.class)
    public ResponseEntity<ErrorResponse> handleInvalidTransition(
            InvalidPaymentTransitionException exception, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "invalid_state_transition", exception.getMessage(), request);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleConcurrentUpdate(
            OptimisticLockingFailureException exception, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "concurrent_update",
                "The payment was modified concurrently. Re-read it and retry.", request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("Validation failed");
        return build(HttpStatus.BAD_REQUEST, "validation_error", message, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(
            ConstraintViolationException exception, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "validation_error", exception.getMessage(), request);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(
            MissingRequestHeaderException exception, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "missing_header",
                "Required header '" + exception.getHeaderName() + "' is missing", request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> handleMalformedRequest(Exception exception, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "malformed_request", "Request could not be parsed", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception exception, HttpServletRequest request) {
        // Spring's own MVC exceptions (unknown path, bad method, unsupported media type) implement
        // ErrorResponse and already carry the right status. Without this they would all be
        // flattened to 500 by the catch-all below, turning a 404 into a fake server error.
        if (exception instanceof org.springframework.web.ErrorResponse springError) {
            HttpStatusCode status = springError.getStatusCode();
            return build(status, status.is4xxClientError() ? "not_found" : "internal_error",
                    "The request could not be handled", request);
        }

        // Log the detail, return a generic message: internals must not leak to merchants.
        log.error("Unhandled exception on {}", request.getRequestURI(), exception);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "An unexpected error occurred", request);
    }

    private ResponseEntity<ErrorResponse> build(
            HttpStatusCode status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(new ErrorResponse(code, message, request.getRequestURI(), OffsetDateTime.now()));
    }
}
