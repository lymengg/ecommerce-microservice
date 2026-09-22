package com.ecommerce.common.error;

/**
 * A downstream dependency could not be reached inside the caller's budget —
 * connection refused, connect/response timeout, pool or bulkhead exhaustion, an
 * open circuit breaker, or a 5xx that survived the retries.
 *
 * <p>Deliberately distinct from the business errors ({@link ConflictException},
 * {@link InsufficientStockException}, {@link NotFoundException}): those say "the
 * request was wrong", this says "the system could not answer, try later". The
 * distinction is what lets the checkout saga compensate on a business rejection
 * and also on an unreachable dependency, instead of leaving an order stranded
 * in {@code PENDING} (Phase 7, ADR-017).
 *
 * <p>Rendered as RFC 9457 {@code 503 Service Unavailable} by
 * {@link GlobalExceptionHandler}.
 */
public class ServiceUnavailableException extends RuntimeException {

    public ServiceUnavailableException(String message) {
        super(message);
    }

    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
