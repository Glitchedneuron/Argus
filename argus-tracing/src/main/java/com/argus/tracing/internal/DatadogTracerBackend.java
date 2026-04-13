package com.argus.tracing.internal;

import com.argus.tracing.ArgusSpan;
import com.argus.tracing.ArgusTracer;
import com.argus.tracing.SpanKind;
import com.argus.tracing.SpanStatus;
import com.argus.tracing.TracerConfig;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.util.GlobalTracer;

import java.util.function.Supplier;

/**
 * {@link ArgusTracer} backed by the OpenTracing API (Datadog backend).
 *
 * <p>Uses {@link GlobalTracer#get()} to obtain the active tracer.  The GlobalTracer is
 * populated automatically by the Datadog Java agent at JVM startup, or programmatically
 * via {@code GlobalTracer.register()} when using {@code dd-trace-java} without an agent.
 * If neither is present the OpenTracing {@code NoopTracer} is returned and all spans are
 * silently discarded.</p>
 *
 * <h2>Log-trace correlation</h2>
 * <p>With the Datadog Java agent running, the agent injects {@code dd.trace_id} and
 * {@code dd.span_id} into the MDC automatically for all log frameworks it instruments.
 * Without the agent, call {@link ArgusSpan#traceId()} / {@link ArgusSpan#spanId()} and
 * attach the values manually to your log events.</p>
 *
 * <h2>span.kind tag</h2>
 * <p>Datadog displays the span kind via the {@code span.kind} tag.  This backend sets
 * it automatically on every span.</p>
 */
public final class DatadogTracerBackend implements ArgusTracer {

    private final Tracer tracer;

    public DatadogTracerBackend(TracerConfig config) {
        this.tracer = GlobalTracer.get();
    }

    // ---- Span creation ------------------------------------------------------

    @Override
    public ArgusSpan startSpan(String spanName) {
        return startSpan(spanName, SpanKind.INTERNAL);
    }

    @Override
    public ArgusSpan startSpan(String spanName, SpanKind kind) {
        Span  span  = tracer.buildSpan(spanName)
                            .withTag("span.kind", kind.name().toLowerCase())
                            .start();
        Scope scope = tracer.activateSpan(span);
        return new DatadogArgusSpan(span, scope);
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

    /**
     * Returns the active Datadog trace ID via {@code CorrelationIdentifier} reflection.
     * Returns empty string when {@code dd-trace-api} is not on the runtime classpath.
     */
    @Override
    public String currentTraceId() {
        return correlationId("getTraceId");
    }

    /**
     * Returns the active Datadog span ID via {@code CorrelationIdentifier} reflection.
     * Returns empty string when {@code dd-trace-api} is not on the runtime classpath.
     */
    @Override
    public String currentSpanId() {
        return correlationId("getSpanId");
    }

    // ---- Lifecycle ----------------------------------------------------------

    @Override
    public void shutdown() {
        // GlobalTracer does not expose a shutdown method — the agent manages the lifecycle.
        // When using dd-trace-java programmatically the JVM shutdown hook handles flushing.
    }

    // -------------------------------------------------------------------------

    private static String correlationId(String methodName) {
        try {
            Class<?> cls = Class.forName("datadog.trace.api.CorrelationIdentifier");
            Object result = cls.getMethod(methodName).invoke(null);
            return result != null ? result.toString() : "";
        } catch (ReflectiveOperationException | ClassNotFoundException ignored) {
            return "";
        }
    }
}
