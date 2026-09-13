package com.ecommerce.catalog.shared.error;

import com.ecommerce.catalog.product.domain.ProductDomainException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.net.URI;
import java.time.Instant;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ProductDomainException.class)
    public ResponseEntity<ProblemDetail> handleProductDomainException(
            ProductDomainException ex,
            WebRequest request
    ) {
        ProblemDetail problemDetail = new ProblemDetail(
                URI.create("https://api.ecommerce.com/errors/product-domain").toString(),
                "Product Domain Error",
                HttpStatus.BAD_REQUEST.value(),
                ex.getMessage(),
                request.getDescription(false),
                Instant.now(),
                List.of()
        );
        return ResponseEntity.badRequest().body(problemDetail);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidationExceptions(
            MethodArgumentNotValidException ex,
            WebRequest request
    ) {
        List<ProblemDetail.FieldError> fieldErrors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> new ProblemDetail.FieldError(error.getField(), error.getDefaultMessage()))
                .toList();

        ProblemDetail problemDetail = new ProblemDetail(
                URI.create("https://api.ecommerce.com/errors/validation").toString(),
                "Validation Error",
                HttpStatus.BAD_REQUEST.value(),
                "Request validation failed",
                request.getDescription(false),
                Instant.now(),
                fieldErrors
        );
        return ResponseEntity.badRequest().body(problemDetail);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGenericException(
            Exception ex,
            WebRequest request
    ) {
        ProblemDetail problemDetail = new ProblemDetail(
                URI.create("https://api.ecommerce.com/errors/internal").toString(),
                "Internal Server Error",
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "An unexpected error occurred",
                request.getDescription(false),
                Instant.now(),
                List.of()
        );
        return ResponseEntity.internalServerError().body(problemDetail);
    }
}
