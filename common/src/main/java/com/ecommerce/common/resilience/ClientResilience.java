package com.ecommerce.common.resilience;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;

/**
 * The resolved resilience policy for one downstream dependency: its transport
 * limits plus the (optional) circuit breaker, retry and bulkhead instances.
 *
 * <p>One instance per dependency, never one shared instance — a global breaker
 * would let a failing payment-service open the circuit for inventory as well
 * (ADR-017).
 */
public record ClientResilience(
        String dependency,
        HttpLimits http,
        CircuitBreaker circuitBreaker,
        Retry retry,
        Bulkhead bulkhead
) {

    /**
     * Timeouts and a bounded pool, with no breaker/retry/bulkhead. Used by
     * tests and by any client that must exist outside a Spring context.
     */
    public static ClientResilience plain(String dependency, HttpLimits http) {
        return new ClientResilience(dependency, http, null, null, null);
    }
}
