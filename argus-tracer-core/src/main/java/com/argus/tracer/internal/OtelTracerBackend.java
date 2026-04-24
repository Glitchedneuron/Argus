package com.argus.tracer.internal;

import com.argus.tracer.ArgusSpan;
import com.argus.tracer.ArgusTracer;
import com.argus.tracer.SpanKind;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.OpenTelemetrySdk;

import java.util.function.Supplier;

/** {@link ArgusTracer} backed by the OTel SDK. Used for both OTEL and DATADOG backends. */
public final class OtelTracerBackend implements ArgusTracer {

    private static final String SCOPE_NAME    = "com.argus.tracer";
    private static final String SCOPE_VERSION = "1.0.0";

    private final Tracer           tracer;
    /** Non-null only when this instance owns the SDK lifecycle. */
    private final OpenTelemetrySdk ownedSdk;

    /**
     * @param ot       the OpenTelemetry instance to obtain tracers from
     * @param ownedSdk the SDK to flush+close on {@link #shutdown()}, or {@code null} when
     *                 wrapping an externally managed instance (e.g. GlobalOpenTelemetry)
     */
    public OtelTracerBackend(OpenTelemetry ot, OpenTelemetrySdk ownedSdk) {
        this.tracer   = ot.getTracer(SCOPE_NAME, SCOPE_VERSION);
        this.ownedSdk = ownedSdk;
    }

    @Override
    public ArgusSpan startSpan(String spanName) {
        return startSpan(spanName, SpanKind.INTERNAL);
    }

    @Override
    public ArgusSpan startSpan(String spanName, SpanKind kind) {
        Span  span  = tracer.spanBuilder(spanName).setSpanKind(toOtel(kind)).startSpan();
        return new OtelArgusSpan(span, span.makeCurrent());
    }

    @Override
    public <T> T trace(String spanName, Supplier<T> block) {
        try (ArgusSpan span = startSpan(spanName)) {
            try {
                return block.get();
            } catch (RuntimeException e) {
                span.recordException(e);
                throw e;
            }
        }
    }

    @Override
    public void trace(String spanName, Runnable block) {
        trace(spanName, () -> { block.run(); return null; });
    }

    @Override
    public String currentTraceId() {
        Span span = Span.fromContext(Context.current());
        return span.getSpanContext().isValid() ? span.getSpanContext().getTraceId() : "";
    }

    @Override
    public String currentSpanId() {
        Span span = Span.fromContext(Context.current());
        return span.getSpanContext().isValid() ? span.getSpanContext().getSpanId() : "";
    }

    @Override
    public void shutdown() {
        if (ownedSdk != null) {
            ownedSdk.getSdkTracerProvider().forceFlush();
            ownedSdk.close();
        }
    }

    private static io.opentelemetry.api.trace.SpanKind toOtel(SpanKind kind) {
        return switch (kind) {
            case SERVER   -> io.opentelemetry.api.trace.SpanKind.SERVER;
            case CLIENT   -> io.opentelemetry.api.trace.SpanKind.CLIENT;
            case PRODUCER -> io.opentelemetry.api.trace.SpanKind.PRODUCER;
            case CONSUMER -> io.opentelemetry.api.trace.SpanKind.CONSUMER;
            case INTERNAL -> io.opentelemetry.api.trace.SpanKind.INTERNAL;
        };
    }
}
