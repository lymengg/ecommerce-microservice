package com.ecommerce.common.tracing;

/**
 * Shared names for the edge correlation id.
 *
 * <p>Defined once because the gateway mints the id and every service reads it:
 * a divergence between the two would break correlation silently, with no
 * compile error to catch it.
 */
public final class Correlation {

    /** Request/response header carrying the id. Set by the gateway, echoed to clients. */
    public static final String HEADER = "X-Correlation-Id";

    /** SLF4J MDC key, referenced by the {@code logging.pattern.console} in each service. */
    public static final String MDC_KEY = "correlationId";

    /** Span attribute name, so a trace can be found by a user-quoted correlation id. */
    public static final String SPAN_ATTRIBUTE = "ecommerce.correlation_id";

    private Correlation() {
    }
}
