package com.landgo.paymentservice.exception;

import com.landgo.paymentservice.dto.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleApiException(ApiException ex) {
        log.warn("API Exception: {} - Code: {}", ex.getMessage(), ex.getErrorCode());
        return ResponseEntity.status(ex.getStatus()).body(ApiResponse.error(ex.getMessage(), ex.getErrorCode()));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        log.debug("Validation failed: {}", ex.getMessage());
        java.util.Map<String, java.util.List<String>> details = ex.getBindingResult().getFieldErrors().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        org.springframework.validation.FieldError::getField,
                        java.util.stream.Collectors.mapping(org.springframework.validation.FieldError::getDefaultMessage, java.util.stream.Collectors.toList())
                ));
        return ResponseEntity.badRequest().body(ApiResponse.error("Validation failed", "VALIDATION_ERROR", details));
    }
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(org.springframework.security.access.AccessDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());
        return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("You do not have permission to access this resource", "ACCESS_DENIED"));
    }
    @ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthException(org.springframework.security.core.AuthenticationException ex) {
        log.warn("Authentication failed: {}", ex.getMessage());
        return ResponseEntity.status(org.springframework.http.HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error(ex.getMessage(), "UNAUTHORIZED"));
    }
    /**
     * Unknown URL paths.
     *
     * <p>Without this, {@code NoResourceFoundException} falls through to the catch-all below and
     * every mistyped or outdated URL answers 500, which reads as a server fault and sends callers
     * hunting for a bug that is not there. A 404 says what is actually wrong.
     */
    @ExceptionHandler({
            org.springframework.web.servlet.resource.NoResourceFoundException.class,
            org.springframework.web.servlet.NoHandlerFoundException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleNotFound(Exception ex) {
        log.warn("No handler for request: {}", ex.getMessage());
        return ResponseEntity.status(org.springframework.http.HttpStatus.NOT_FOUND)
                .body(ApiResponse.error("Endpoint not found", "ENDPOINT_NOT_FOUND"));
    }

    /**
     * Wrong HTTP verb against a path that does exist — POST where only GET is mapped, say.
     * Also a client error, and also indistinguishable from a server fault without this.
     */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(
            org.springframework.web.HttpRequestMethodNotSupportedException ex) {
        log.warn("Method not supported: {}", ex.getMessage());
        return ResponseEntity.status(org.springframework.http.HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.error(ex.getMessage(), "METHOD_NOT_ALLOWED"));
    }

    /**
     * A path variable or query param that cannot be converted to the declared type — most often a
     * malformed UUID. The caller sent something wrong, so this is a 400; without it the conversion
     * failure reaches the catch-all and reports 500, which points debugging at the wrong system.
     *
     * <p>Only the parameter name is echoed back. The offending value is left out on purpose: it is
     * attacker-controlled and would otherwise be reflected verbatim into the response body.
     */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex) {
        log.warn("Type mismatch for parameter '{}': {}", ex.getName(), ex.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("Invalid value for parameter '" + ex.getName() + "'", "VALIDATION_ERROR"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneral(Exception ex) {
        log.error("Unhandled exception occurred: ", ex);
        return ResponseEntity.internalServerError()
                .body(ApiResponse.error("Internal server error: " + ex.getMessage(), "INTERNAL_ERROR"));
    }
}
