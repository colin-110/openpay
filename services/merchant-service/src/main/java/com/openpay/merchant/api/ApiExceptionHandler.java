package com.openpay.merchant.api;

import com.openpay.merchant.application.MerchantAlreadyExistsException;
import com.openpay.merchant.application.MerchantNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MerchantNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(MerchantNotFoundException exception, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "merchant_not_found", exception.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(MerchantAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleConflict(
            MerchantAlreadyExistsException exception, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "merchant_already_exists", exception.getMessage(), request.getRequestURI());
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
