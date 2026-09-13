package com.ecommerce.catalog.product.presentation;

import com.ecommerce.catalog.product.application.DuplicateSkuException;
import com.ecommerce.catalog.product.application.ProductNotFoundException;
import com.ecommerce.catalog.product.domain.ProductDomainException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.Instant;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleProductNotFound(ProductNotFoundException ex) {
        ProblemDetail problemDetail = new ProblemDetail(
                URI.create("https://api.ecommerce.com/errors/product-not-found"),
                "Product Not Found",
                HttpStatus.NOT_FOUND.value(),
                ex.getMessage(),
                null,
                Instant.now(),
                List.of()
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problemDetail);
    }

    @ExceptionHandler(DuplicateSkuException.class)
    public ResponseEntity<ProblemDetail> handleDuplicateSku(DuplicateSkuException ex) {
        ProblemDetail problemDetail = new ProblemDetail(
                URI.create("https://api.ecommerce.com/errors/duplicate-sku"),
                "Duplicate SKU",
                HttpStatus.CONFLICT.value(),
                ex.getMessage(),
                null,
                Instant.now(),
                List.of()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problemDetail);
    }

    @ExceptionHandler(ProductDomainException.class)
    public ResponseEntity<ProblemDetail> handleProductDomainException(ProductDomainException ex) {
        ProblemDetail problemDetail = new ProblemDetail(
                URI.create("https://api.ecommerce.com/errors/invalid-product-state"),
                "Invalid Product State",
                HttpStatus.BAD_REQUEST.value(),
                ex.getMessage(),
                null,
                Instant.now(),
                List.of()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problemDetail);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidationErrors(MethodArgumentNotValidException ex) {
        List<ProblemDetail.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new ProblemDetail.FieldError(error.getField(), error.getDefaultMessage()))
                .toList();

        ProblemDetail problemDetail = new ProblemDetail(
                URI.create("https://api.ecommerce.com/errors/validation-error"),
                "Validation Error",
                HttpStatus.BAD_REQUEST.value(),
                "Request validation failed",
                null,
                Instant.now(),
                fieldErrors
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problemDetail);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> handleIllegalArgument(IllegalArgumentException ex) {
        ProblemDetail problemDetail = new ProblemDetail(
                URI.create("https://api.ecommerce.com/errors/invalid-request"),
                "Invalid Request",
                HttpStatus.BAD_REQUEST.value(),
                ex.getMessage(),
                null,
                Instant.now(),
                List.of()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problemDetail);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGenericException(Exception ex) {
        ProblemDetail problemDetail = new ProblemDetail(
                URI.create("https://api.ecommerce.com/errors/internal-server-error"),
                "Internal Server Error",
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "An unexpected error occurred",
                null,
                Instant.now(),
                List.of()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problemDetail);
    }
}
