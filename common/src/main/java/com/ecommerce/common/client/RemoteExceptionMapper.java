package com.ecommerce.common.client;

import com.ecommerce.common.error.ConflictException;
import com.ecommerce.common.error.InsufficientStockException;
import com.ecommerce.common.error.NotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ProblemDetail;
import org.springframework.web.client.RestClientResponseException;

/**
 * Maps error responses thrown by a RestClient into the domain exceptions used
 * across services. Relies on every service exposing RFC 9457 ProblemDetail
 * bodies via the shared {@code GlobalExceptionHandler}.
 */
public final class RemoteExceptionMapper {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private RemoteExceptionMapper() {
    }

    public static RuntimeException from(RestClientResponseException ex) {
        ProblemDetail problem = readProblem(ex);
        String detail = problem != null && problem.getDetail() != null ? problem.getDetail() : ex.getMessage();

        if (problem != null && problem.getType() != null
                && "urn:problem:insufficient-stock".equals(problem.getType().toString())) {
            return new InsufficientStockException(detail);
        }
        int status = ex.getStatusCode().value();
        if (status == 404) {
            return new NotFoundException(detail);
        }
        if (status == 409) {
            return new ConflictException(detail);
        }
        return ex;
    }

    private static ProblemDetail readProblem(RestClientResponseException ex) {
        try {
            String body = ex.getResponseBodyAsString();
            if (body == null || body.isBlank()) {
                return null;
            }
            return OBJECT_MAPPER.readValue(body, ProblemDetail.class);
        } catch (Exception ignored) {
            return null;
        }
    }
}
