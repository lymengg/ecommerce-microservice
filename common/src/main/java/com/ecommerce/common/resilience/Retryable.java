package com.ecommerce.common.resilience;

import org.springframework.web.client.RestClient;

/**
 * Per-call retry opt-in (Phase 7, ADR-019).
 *
 * <p>Retry safety is a property of the *operation*, not of the service: a
 * {@code GET /api/v1/cart/{id}/lines} is free to retry, while a
 * {@code POST .../reservations} is only safe because it was made idempotent.
 * The HTTP method alone cannot express that — order creation and payment
 * initiation are POSTs that carry an {@code Idempotency-Key} and must be
 * retried, while an order state transition is a POST that must not be.
 *
 * <p>So retries are opt-in per request and the default is conservative: safe
 * methods (GET/HEAD/OPTIONS) retry, everything else does not unless the client
 * says so. A client that opts in is asserting the operation is idempotent —
 * that assertion is the thing the ADR records per endpoint.
 *
 * <pre>{@code
 * Retryable.yes(restClient.post().uri("...")).retrieve()...
 * }</pre>
 */
public final class Retryable {

    /** Request attribute read by {@link ResilienceRequestInterceptor}. */
    public static final String ATTRIBUTE = Retryable.class.getName();

    private Retryable() {
    }

    /** Marks a call as safe to retry (the operation is idempotent). */
    public static <S extends RestClient.RequestHeadersSpec<?>> S yes(S spec) {
        spec.attribute(ATTRIBUTE, Boolean.TRUE);
        return spec;
    }

    /**
     * Marks a call as not retryable, overriding the safe-method default. Used
     * to make the decision visible at the call site rather than leaving it
     * implicit in the HTTP method.
     */
    public static <S extends RestClient.RequestHeadersSpec<?>> S no(S spec) {
        spec.attribute(ATTRIBUTE, Boolean.FALSE);
        return spec;
    }
}
