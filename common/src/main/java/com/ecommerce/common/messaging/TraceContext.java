package com.ecommerce.common.messaging;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;

import java.util.HashMap;
import java.util.Map;

/**
 * Carries the W3C trace context across the outbox boundary (doc 08 §2).
 *
 * <p>The OpenTelemetry Java agent propagates trace context automatically for
 * <em>synchronous</em> hops (HTTP) and for Kafka sends made inside an active
 * span. The outbox publisher breaks that: it runs on a scheduler thread, long
 * after the request that recorded the event has finished, so at send time there
 * is no active span to inherit. The trace would start over at the broker and
 * the async consumer would show up as an unrelated trace.
 *
 * <p>The fix is the standard one: capture the {@code traceparent} when the
 * event is <em>recorded</em> (inside the request), store it on the outbox row,
 * and restore it as the current context just before publishing. The agent's
 * Kafka instrumentation then injects a child of the original span, so a
 * checkout remains one trace across the produce/consume boundary.
 *
 * <p>Without the agent (unit tests) every call here is a no-op: {@code Span.current()}
 * is invalid and {@link #capture()} returns {@code null}.
 */
public final class TraceContext {

    private static final TextMapPropagator PROPAGATOR = W3CTraceContextPropagator.getInstance();
    private static final String TRACEPARENT = "traceparent";

    /** {@link TextMapGetter} is not a functional interface (it also exposes keys). */
    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    };

    private TraceContext() {
    }

    /** The current W3C {@code traceparent}, or {@code null} when untraced. */
    public static String capture() {
        Map<String, String> carrier = new HashMap<>();
        PROPAGATOR.inject(Context.current(), carrier, Map::put);
        return carrier.get(TRACEPARENT);
    }

    /** The current trace id (32 hex chars), or {@code null} when untraced. */
    public static String currentTraceId() {
        SpanContext spanContext = Span.current().getSpanContext();
        return spanContext.isValid() ? spanContext.getTraceId() : null;
    }

    /** Extracts the trace id from a {@code traceparent}, or {@code null}. */
    public static String traceIdOf(String traceParent) {
        if (traceParent == null) {
            return null;
        }
        String[] parts = traceParent.split("-");
        return parts.length == 4 ? parts[1] : null;
    }

    /**
     * Runs {@code action} with the given {@code traceparent} as the current
     * context, so anything the action does (a Kafka send, a log line) is
     * attributed to the original trace. A blank value runs untraced.
     */
    public static void withTraceParent(String traceParent, Runnable action) {
        try (Scope ignored = extract(traceParent).makeCurrent()) {
            action.run();
        }
    }

    private static Context extract(String traceParent) {
        if (traceParent == null || traceParent.isBlank()) {
            return Context.root();
        }
        return PROPAGATOR.extract(Context.root(), Map.of(TRACEPARENT, traceParent), GETTER);
    }
}
