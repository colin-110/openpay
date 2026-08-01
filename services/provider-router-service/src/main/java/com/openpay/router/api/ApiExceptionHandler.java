package com.openpay.router.api;

import com.openpay.router.application.InvalidRoutingRuleException;
import com.openpay.router.application.RoutingRuleNotFoundException;
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

/**
 * This service had no exception handler until the routing table gave it something to refuse.
 *
 * <p>Before it, a duplicate rule or an inverted amount band surfaced as a 500 — telling an operator
 * the platform is broken when what they actually did was send a request it will not accept, which is
 * the difference between "try again" and "fix your request".
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(RoutingRuleNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(
            RoutingRuleNotFoundException exception, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "routing_rule_not_found", exception.getMessage(), request);
    }

    @ExceptionHandler(InvalidRoutingRuleException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRule(
            InvalidRoutingRuleException exception, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "invalid_routing_rule", exception.getMessage(), request);
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
        // Spring's own MVC exceptions already carry the right status; flattening them here would
        // turn an unknown path into a fabricated server error.
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
