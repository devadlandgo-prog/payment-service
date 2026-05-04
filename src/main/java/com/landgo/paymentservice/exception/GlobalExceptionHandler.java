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
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneral(Exception ex) {
        log.error("Unhandled exception occurred: ", ex);
        return ResponseEntity.internalServerError()
                .body(ApiResponse.error("Internal server error", "INTERNAL_ERROR"));
    }
}
