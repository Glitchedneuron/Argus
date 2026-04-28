package com.argus.tracer.internal;

import com.argus.tracer.ArgusSpan;
import com.argus.tracer.ArgusTracer;
import com.argus.tracer.SpanKind;
import datadog.trace.api.CorrelationIdentifier;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.Tracer;

import java.util.function.Supplier;

/** {@link ArgusTracer} backed by the Datadog native tracer (dd-trace-java / OpenTracing API). */
public final class DatadogSdkTracerBackend implements ArgusTracer {

    private final Tracer tracer;

    public DatadogSdkTracerBackend(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public ArgusSpan startSpan(String spanName) {
        return startSpan(spanName, SpanKind.INTERNAL);
    }

    @Override
    public ArgusSpan startSpan(String spanName, SpanKind kind) {
        Span  span  = tracer.buildSpan(spanName).withTag("span.kind", toSpanKind(kind)).start();
        Scope scope = tracer.activateSpan(span);
        return new DatadogSdkArgusSpan(span, scope);
    }

    @Override
    public <T> T trace(String spanName, Supplier<T> block) {
        try (ArgusSpan span = startSpan(spanName)) {
            try {
                return block.get();
            } catch (RuntimeException exception) {
                span.recordException(exception);
                throw exception;
            }
        }
    }

    @Override
    public void trace(String spanName, Runnable block) {
        trace(spanName, () -> { block.run(); return null; });
    }

    @Override
    public String currentTraceId() {
        return CorrelationIdentifier.getTraceId();
    }

    @Override
    public String currentSpanId() {
        return CorrelationIdentifier.getSpanId();
    }

    @Override
    public void shutdown() {
        tracer.close();
    }

    private static String toSpanKind(SpanKind kind) {
        return switch (kind) {
            case SERVER   -> "server";
            case CLIENT   -> "client";
            case PRODUCER -> "producer";
            case CONSUMER -> "consumer";
            case INTERNAL -> "internal";
        };
    }
}
