package com.argus.tracing.internal;

import com.argus.tracing.ArgusSpan;
import com.argus.tracing.SpanStatus;
import io.opentracing.Scope;
import io.opentracing.Span;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link ArgusSpan} backed by an OpenTracing {@link Span} (Datadog backend).
 *
 * <p>Wraps both the span and the {@link Scope} that makes it the active span on the
 * current thread.  {@link #end()} closes the scope first then finishes the span —
 * the same ordering used by OTel's SDK.</p>
 *
 * <h2>Trace / span ID retrieval</h2>
 * <p>{@link #traceId()} and {@link #spanId()} attempt to read the IDs from the
 * Datadog {@code CorrelationIdentifier} API via reflection so that {@code dd-trace-api}
 * is not a hard compile-time dependency of this module.  If the class is absent an
 * empty string is returned.</p>
 */
final class DatadogArgusSpan implements ArgusSpan {

    private final Span          span;
    private final Scope         scope;
    private final AtomicBoolean ended = new AtomicBoolean(false);

    DatadogArgusSpan(Span span, Scope scope) {
        this.span  = span;
        this.scope = scope;
    }

    // ---- Attributes / tags --------------------------------------------------

    @Override
    public ArgusSpan tag(String key, String value) {
        span.setTag(key, value);
        return this;
    }

    @Override
    public ArgusSpan tag(String key, long value) {
        span.setTag(key, value);
        return this;
    }

    @Override
    public ArgusSpan tag(String key, boolean value) {
        span.setTag(key, value);
        return this;
    }

    // ---- Error recording ----------------------------------------------------

    /**
     * Records exception details as standard Datadog error tags:
     * {@code error=true}, {@code error.type}, {@code error.message}, {@code error.stack}.
     */
    @Override
    public ArgusSpan recordException(Throwable t) {
        span.setTag("error", true);
        span.setTag("error.type", t.getClass().getName());
        if (t.getMessage() != null) {
            span.setTag("error.message", t.getMessage());
        }
        // Truncated stacktrace — Datadog UI shows the first ~4 KB
        StringWriter sw = new StringWriter(2048);
        t.printStackTrace(new PrintWriter(sw));
        String stack = sw.toString();
        span.setTag("error.stack", stack.length() > 4096 ? stack.substring(0, 4096) : stack);
        return this;
    }

    // ---- Status -------------------------------------------------------------

    @Override
    public ArgusSpan setStatus(SpanStatus status) {
        if (status == SpanStatus.ERROR) span.setTag("error", true);
        return this;
    }

    @Override
    public ArgusSpan setStatus(SpanStatus status, String description) {
        if (status == SpanStatus.ERROR) {
            span.setTag("error", true);
            if (description != null) span.setTag("error.message", description);
        }
        return this;
    }

    // ---- Context identifiers ------------------------------------------------

    /**
     * Returns the Datadog trace ID via {@code datadog.trace.api.CorrelationIdentifier}
     * (requires {@code dd-trace-api} on the classpath at runtime).
     * Returns an empty string when not available.
     */
    @Override
    public String traceId() {
        return correlationId("getTraceId");
    }

    /**
     * Returns the Datadog span ID via {@code datadog.trace.api.CorrelationIdentifier}.
     * Returns an empty string when not available.
     */
    @Override
    public String spanId() {
        return correlationId("getSpanId");
    }

    // ---- Lifecycle ----------------------------------------------------------

    @Override
    public void end() {
        if (ended.compareAndSet(false, true)) {
            scope.close();
            span.finish();
        }
    }

    @Override
    public void close() {
        end();
    }

    // -------------------------------------------------------------------------

    /**
     * Reflectively calls a static zero-arg method on {@code datadog.trace.api.CorrelationIdentifier}
     * and returns its String result, or {@code ""} if the class / method is absent.
     */
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
