package com.openpay.fraud.api;

import com.openpay.fraud.application.DecisionNotFoundException;
import com.openpay.fraud.application.InvalidRuleException;
import com.openpay.fraud.application.RuleNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(DecisionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleDecisionNotFound(
            DecisionNotFoundException exception, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "decision_not_found", exception.getMessage(), request);
    }

    @ExceptionHandler(RuleNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleRuleNotFound(
            RuleNotFoundException exception, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "rule_not_found", exception.getMessage(), request);
    }

    @ExceptionHandler(InvalidRuleException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRule(
            InvalidRuleException exception, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "invalid_rule", exception.getMessage(), request);
    }

    /**
     * Resolving a review that was never open, or was already closed. 409 rather than 400: the
     * request was fine when the operator loaded the queue, and someone else got there first.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleConflict(
            IllegalStateException exception, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "review_not_open", exception.getMessage(), request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(
            IllegalArgumentException exception, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "invalid_request", exception.getMessage(), request);
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

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> handleMalformedRequest(Exception exception, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "malformed_request", "Request could not be parsed", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception exception, HttpServletRequest request) {
        // Spring's own MVC exceptions already carry the right status; flattening them to 500 would
        // turn an unknown path into a fake server error.
        if (exception instanceof org.springframework.web.ErrorResponse springError) {
            HttpStatusCode status = springError.getStatusCode();
            return build(status, status.is4xxClientError() ? "not_found" : "internal_error",
                    "The request could not be handled", request);
        }

        log.error("Unhandled exception on {}", request.getRequestURI(), exception);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "An unexpected error occurred", request);
    }

    private ResponseEntity<ErrorResponse> build(
            HttpStatusCode status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(new ErrorResponse(code, message, request.getRequestURI(), OffsetDateTime.now()));
    }
}
