package com.openpay.gateway.api;

import com.openpay.gateway.routing.DownstreamUnavailableException;
import com.openpay.gateway.routing.NoRouteException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(DownstreamUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleDownstreamUnavailable(
            DownstreamUnavailableException exception, HttpServletRequest request) {
        return build(HttpStatus.BAD_GATEWAY, "downstream_unavailable",
                "The upstream service is temporarily unavailable", request);
    }

    @ExceptionHandler(NoRouteException.class)
    public ResponseEntity<ErrorResponse> handleNoRoute(NoRouteException exception, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "no_route", "No route is configured for this path", request);
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

        log.error("Unhandled exception on {}", request.getRequestURI(), exception);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "An unexpected error occurred", request);
    }

    private ResponseEntity<ErrorResponse> build(
            HttpStatusCode status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(new ErrorResponse(code, message, request.getRequestURI(), OffsetDateTime.now()));
    }
}
