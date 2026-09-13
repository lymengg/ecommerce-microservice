package com.ecommerce.catalog.product.presentation;

import java.net.URI;
import java.time.Instant;
import java.util.List;

public record ProblemDetail(
        URI type,
        String title,
        int status,
        String detail,
        String instance,
        Instant timestamp,
        List<FieldError> errors
) {
    public record FieldError(String field, String message) {
    }

    public static ProblemDetail of(int status, String title, String detail) {
        return new ProblemDetail(
                URI.create("about:blank"),
                title,
                status,
                detail,
                null,
                Instant.now(),
                List.of()
        );
    }

    public static ProblemDetail of(int status, String title, String detail, List<FieldError> errors) {
        return new ProblemDetail(
                URI.create("about:blank"),
                title,
                status,
                detail,
                null,
                Instant.now(),
                errors
        );
    }
}
