package com.openpay.vault.api;

import com.openpay.security.InsufficientAuthorityException;
import com.openpay.vault.application.InvalidInstrumentException;
import jakarta.servlet.http.HttpServletRequest;
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

/**
 * Error handling with one rule on top of the usual ones: nothing that came in the request body is
 * ever put in the response or the log. This service handles card numbers, and the ordinary Spring
 * behaviour of quoting the rejected value is exactly wrong here.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(InvalidInstrumentException.class)
    public ResponseEntity<ErrorResponse> handleInvalidInstrument(
            InvalidInstrumentException exception, HttpServletRequest request) {
        // 422 rather than 400: the request parsed perfectly well, the instrument in it is just not
        // one that can be tokenised. Not logged at all — a rejected card is the customer's business,
        // it happens constantly on a real checkout, and there is nothing here an operator can act on.
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_instrument",
                exception.getMessage(), exception.getField(), request);
    }

    @ExceptionHandler(InsufficientAuthorityException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientAuthority(
            InsufficientAuthorityException exception, HttpServletRequest request) {
        log.warn("Refused {} for a credential with authority '{}'",
                request.getRequestURI(), exception.getAuthority());
        return build(HttpStatus.FORBIDDEN, "insufficient_authority", exception.getMessage(), null, request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        // The field name and this service's own message, never Spring's default rendering, which
        // includes the rejected value.
        String field = exception.getBindingResult().getFieldError() == null
                ? null
                : exception.getBindingResult().getFieldError().getField();
        String message = exception.getBindingResult().getFieldError() == null
                ? "The request is not valid"
                : exception.getBindingResult().getFieldError().getDefaultMessage();
        return build(HttpStatus.BAD_REQUEST, "validation_failed", message, field, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpServletRequest request) {
        // Deliberately takes no exception argument: the message of this one can quote the malformed
        // body, and the malformed body may be most of a card number.
        return build(HttpStatus.BAD_REQUEST, "malformed_request", "The request body could not be read",
                null, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception exception, HttpServletRequest request) {
        // The class name and the path, not the message. An unexpected failure inside this service
        // has a request body somewhere in its context, and the whole point of the boundary is that
        // it does not end up in a log aggregator.
        log.error("Unhandled {} on {}", exception.getClass().getName(), request.getRequestURI());
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "An unexpected error occurred",
                null, request);
    }

    private ResponseEntity<ErrorResponse> build(
            HttpStatusCode status, String code, String message, String field, HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(new ErrorResponse(code, message, field, request.getRequestURI(), OffsetDateTime.now()));
    }
}
