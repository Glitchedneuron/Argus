package com.argus.tracing.internal;

import com.argus.tracing.ArgusSpan;
import com.argus.tracing.ArgusTracer;
import com.argus.tracing.SpanKind;
import com.argus.tracing.SpanStatus;
import com.argus.tracing.TracerConfig;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.SdkTracerProvider;

import java.util.function.Supplier;

/**
 * {@link ArgusTracer} backed by the OpenTelemetry SDK.
 *
 * <p>Log-trace correlation is automatic: {@code ArgusLogger.emit()} calls made while a
 * span created by this backend is active automatically include the span's
 * {@code trace_id} and {@code span_id}, because both use the same OTel
 * {@code Context} propagation.</p>
 *
 * <h2>Exporter selection</h2>
 * <ul>
 *   <li>If an existing {@link OpenTelemetry} instance is provided at construction:
 *       its tracer is used as-is (no separate exporter is created).</li>
 *   <li>Otherwise: {@code OTEL_EXPORTER_OTLP_ENDPOINT} set → OTLP/HTTP + batch;
 *       not set → {@link ConsoleSpanExporter} + simple (dev mode).</li>
 * </ul>
 */
public final class OtelTracerBackend implements ArgusTracer {

    private final Tracer            tracer;
    /**
     * Non-null only when this backend owns the provider (i.e. no external OTel was supplied).
     * Null when the caller provided an existing {@link OpenTelemetry} instance.
     */
    private final SdkTracerProvider ownedProvider;

    public OtelTracerBackend(TracerConfig config, OpenTelemetry existingOtel) {
        if (existingOtel != null) {
            this.tracer        = existingOtel.getTracer(
                    OtelTracingInitializer.INSTRUMENTATION_SCOPE,
                    OtelTracingInitializer.INSTRUMENTATION_VERSION);
            this.ownedProvider = null;
        } else {
            var handles        = OtelTracingInitializer.initialize(config);
            this.tracer        = handles.tracer();
            this.ownedProvider = handles.tracerProvider();
        }
    }

    // ---- Span creation ------------------------------------------------------

    @Override
    public ArgusSpan startSpan(String spanName) {
        return startSpan(spanName, SpanKind.INTERNAL);
    }

    @Override
    public ArgusSpan startSpan(String spanName, SpanKind kind) {
        Span  span  = tracer.spanBuilder(spanName)
                            .setSpanKind(toOtelKind(kind))
                            .startSpan();
        Scope scope = span.makeCurrent();
        return new OtelArgusSpan(span, scope);
    }

    // ---- Convenience wrappers -----------------------------------------------

    @Override
    public <T> T trace(String spanName, Supplier<T> block) {
        try (ArgusSpan span = startSpan(spanName)) {
            try {
                return block.get();
            } catch (RuntimeException e) {
                span.recordException(e).setStatus(SpanStatus.ERROR, e.getMessage());
                throw e;
            }
        }
    }

    @Override
    public void trace(String spanName, Runnable block) {
        try (ArgusSpan span = startSpan(spanName)) {
            try {
                block.run();
            } catch (RuntimeException e) {
                span.recordException(e).setStatus(SpanStatus.ERROR, e.getMessage());
                throw e;
            }
        }
    }

    // ---- Active context -----------------------------------------------------

    @Override
    public String currentTraceId() {
        return Span.current().getSpanContext().getTraceId();
    }

    @Override
    public String currentSpanId() {
        return Span.current().getSpanContext().getSpanId();
    }

    // ---- Lifecycle ----------------------------------------------------------

    @Override
    public void shutdown() {
        if (ownedProvider != null) {
            ownedProvider.shutdown();
        }
    }

    // -------------------------------------------------------------------------

    private static io.opentelemetry.api.trace.SpanKind toOtelKind(SpanKind kind) {
        return switch (kind) {
            case INTERNAL -> io.opentelemetry.api.trace.SpanKind.INTERNAL;
            case SERVER   -> io.opentelemetry.api.trace.SpanKind.SERVER;
            case CLIENT   -> io.opentelemetry.api.trace.SpanKind.CLIENT;
            case PRODUCER -> io.opentelemetry.api.trace.SpanKind.PRODUCER;
            case CONSUMER -> io.opentelemetry.api.trace.SpanKind.CONSUMER;
        };
    }
}
