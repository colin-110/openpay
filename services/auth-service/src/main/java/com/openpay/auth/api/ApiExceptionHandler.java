package com.openpay.auth.api;

import com.openpay.auth.application.InvalidApiKeyException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(InvalidApiKeyException.class)
    public ResponseEntity<ErrorResponse> handleInvalidApiKey(
            InvalidApiKeyException exception, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, "invalid_api_key", exception.getMessage(), request.getRequestURI());
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

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String code, String message, String path) {
        return ResponseEntity.status(status)
                .body(new ErrorResponse(code, message, path, OffsetDateTime.now()));
    }
}
