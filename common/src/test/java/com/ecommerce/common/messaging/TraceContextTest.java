package com.ecommerce.common.messaging;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The trace context has to survive the outbox: captured inside the request,
 * stored on the row, restored on a scheduler thread at publish time. These
 * tests pin the round trip with the OpenTelemetry API alone (no agent/SDK), the
 * same way the code behaves in unit tests.
 */
class TraceContextTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN_ID = "00f067aa0ba902b7";

    @Test
    void captureReturnsNullWhenNothingIsBeingTraced() {
        assertThat(TraceContext.capture()).isNull();
        assertThat(TraceContext.currentTraceId()).isNull();
    }

    @Test
    void capturesTheCurrentTraceParentAndRestoresIt() {
        SpanContext spanContext = SpanContext.create(
                TRACE_ID, SPAN_ID, TraceFlags.getSampled(), TraceState.getDefault());

        String traceParent;
        try (Scope ignored = Span.wrap(spanContext).makeCurrent()) {
            traceParent = TraceContext.capture();
        }

        assertThat(traceParent).isEqualTo("00-" + TRACE_ID + "-" + SPAN_ID + "-01");
        assertThat(TraceContext.traceIdOf(traceParent)).isEqualTo(TRACE_ID);

        // Publishing restores it; the trace id inside is the original one.
        TraceContext.withTraceParent(traceParent, () ->
                assertThat(TraceContext.currentTraceId()).isEqualTo(TRACE_ID));
    }

    @Test
    void blankOrMalformedTraceParentsDegradeToUntraced() {
        assertThat(TraceContext.traceIdOf(null)).isNull();
        assertThat(TraceContext.traceIdOf("not-a-traceparent")).isNull();
        TraceContext.withTraceParent(null, () -> assertThat(TraceContext.currentTraceId()).isNull());
        TraceContext.withTraceParent("  ", () -> assertThat(TraceContext.currentTraceId()).isNull());
    }
}
