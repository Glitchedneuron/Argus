package com.argus.tracing.internal;

import com.argus.tracing.ArgusSpan;
import com.argus.tracing.SpanStatus;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link ArgusSpan} backed by an OTel {@link Span}.
 *
 * <p>Created by {@link OtelTracerBackend#startSpan}.  Holds both the span and the
 * {@link Scope} that makes it the active span in the current thread; both are released
 * in {@link #end()}.</p>
 */
final class OtelArgusSpan implements ArgusSpan {

    private final Span          span;
    private final Scope         scope;
    private final AtomicBoolean ended = new AtomicBoolean(false);

    OtelArgusSpan(Span span, Scope scope) {
        this.span  = span;
        this.scope = scope;
    }

    // ---- Attributes ---------------------------------------------------------

    @Override
    public ArgusSpan tag(String key, String value) {
        span.setAttribute(key, value);
        return this;
    }

    @Override
    public ArgusSpan tag(String key, long value) {
        span.setAttribute(key, value);
        return this;
    }

    @Override
    public ArgusSpan tag(String key, boolean value) {
        span.setAttribute(key, value);
        return this;
    }

    // ---- Error recording ----------------------------------------------------

    @Override
    public ArgusSpan recordException(Throwable t) {
        span.recordException(t);
        span.setStatus(StatusCode.ERROR);
        return this;
    }

    // ---- Status -------------------------------------------------------------

    @Override
    public ArgusSpan setStatus(SpanStatus status) {
        span.setStatus(toOtelStatus(status));
        return this;
    }

    @Override
    public ArgusSpan setStatus(SpanStatus status, String description) {
        if (description != null) {
            span.setStatus(toOtelStatus(status), description);
        } else {
            span.setStatus(toOtelStatus(status));
        }
        return this;
    }

    // ---- Context identifiers ------------------------------------------------

    @Override
    public String traceId() {
        return span.getSpanContext().getTraceId();
    }

    @Override
    public String spanId() {
        return span.getSpanContext().getSpanId();
    }

    // ---- Lifecycle ----------------------------------------------------------

    @Override
    public void end() {
        if (ended.compareAndSet(false, true)) {
            // Close the scope FIRST so downstream spans see the correct parent context.
            scope.close();
            span.end();
        }
    }

    @Override
    public void close() {
        end();
    }

    // -------------------------------------------------------------------------

    private static StatusCode toOtelStatus(SpanStatus status) {
        return switch (status) {
            case OK    -> StatusCode.OK;
            case ERROR -> StatusCode.ERROR;
            case UNSET -> StatusCode.UNSET;
        };
    }
}
