package com.ecommerce.common.tracing;

import io.opentelemetry.api.trace.Span;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Bridges the edge correlation id into the SLF4J MDC and onto the active span.
 *
 * <p>The gateway mints {@code X-Correlation-Id} at the edge and echoes it to the
 * client, which makes it the id a user can quote in a support ticket. Trace ids
 * are internal, so the two stay separate concepts: trace context does the
 * <em>propagation</em> (the OpenTelemetry agent handles that automatically),
 * while this filter makes the correlation id <em>searchable</em> — in logs via
 * MDC and in Jaeger via a span attribute.
 *
 * <p>{@link Span#current()} is a no-op when no agent is attached, so this filter
 * is harmless in unit tests and in any deployment that does not enable tracing.
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String correlationId = request.getHeader(Correlation.HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        MDC.put(Correlation.MDC_KEY, correlationId);
        Span.current().setAttribute(Correlation.SPAN_ATTRIBUTE, correlationId);
        response.setHeader(Correlation.HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            // Servlet threads are pooled: leaking MDC would attribute this
            // request's correlation id to the next one on the same thread.
            MDC.remove(Correlation.MDC_KEY);
        }
    }
}
