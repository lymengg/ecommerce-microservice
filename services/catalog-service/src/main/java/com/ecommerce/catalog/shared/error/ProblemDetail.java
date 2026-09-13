package com.ecommerce.catalog.shared.error;

import java.time.Instant;
import java.util.List;

public record ProblemDetail(
        String type,
        String title,
        int status,
        String detail,
        String instance,
        Instant timestamp,
        List<FieldError> errors
) {
    public record FieldError(
            String field,
            String message
    ) {
    }
}
