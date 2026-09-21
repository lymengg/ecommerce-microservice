package com.ecommerce.gateway.filter;

import io.opentelemetry.api.trace.Span;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Ensures every request carries a correlation id: an inbound X-Correlation-Id
 * is propagated, otherwise one is generated. The id is forwarded downstream
 * and echoed on the response so logs can be traced across services.
 *
 * <p>Downstream services pick the id up via
 * {@code com.ecommerce.common.tracing.CorrelationIdFilter}, which puts it in the
 * MDC and on the span.
 */
@Component
public class CorrelationIdFilter implements GlobalFilter, Ordered {

    /**
     * Must stay in sync with {@code com.ecommerce.common.tracing.Correlation.HEADER}.
     * Duplicated rather than imported because the gateway intentionally does not
     * depend on {@code common} — that module brings servlet Spring Web with it,
     * which must not reach a reactive application.
     */
    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    /** Must stay in sync with {@code Correlation.SPAN_ATTRIBUTE}. */
    private static final String SPAN_ATTRIBUTE = "ecommerce.correlation_id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String correlationId = exchange.getRequest().getHeaders().getFirst(CORRELATION_ID_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        ServerHttpRequest request = exchange.getRequest().mutate()
                .header(CORRELATION_ID_HEADER, correlationId)
                .build();
        exchange.getResponse().getHeaders().set(CORRELATION_ID_HEADER, correlationId);

        // Make the id findable in the trace. The reactive chain switches threads,
        // so MDC is not dependable here; the span attribute is the durable link
        // from a user-quoted correlation id back to its trace.
        Span.current().setAttribute(SPAN_ATTRIBUTE, correlationId);

        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
