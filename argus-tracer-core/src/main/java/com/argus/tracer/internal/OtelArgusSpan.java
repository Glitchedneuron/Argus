package com.argus.tracer.internal;

import com.argus.tracer.ArgusSpan;
import com.argus.tracer.SpanStatus;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;

import java.util.concurrent.atomic.AtomicBoolean;

/** {@link ArgusSpan} backed by an OTel {@link Span} + active {@link Scope}. */
final class OtelArgusSpan implements ArgusSpan {

    private final Span          span;
    private final Scope         scope;
    private final AtomicBoolean ended = new AtomicBoolean(false);

    OtelArgusSpan(Span span, Scope scope) {
        this.span  = span;
        this.scope = scope;
    }

    @Override
    public ArgusSpan tag(String key, String value) {
        span.setAttribute(AttributeKey.stringKey(key), value);
        return this;
    }

    @Override
    public ArgusSpan tag(String key, long value) {
        span.setAttribute(AttributeKey.longKey(key), value);
        return this;
    }

    @Override
    public ArgusSpan tag(String key, boolean value) {
        span.setAttribute(AttributeKey.booleanKey(key), value);
        return this;
    }

    @Override
    public ArgusSpan recordException(Throwable throwable) {
        span.recordException(throwable);
        span.setStatus(StatusCode.ERROR);
        return this;
    }

    @Override
    public ArgusSpan status(SpanStatus status) {
        span.setStatus(toOtel(status));
        return this;
    }

    @Override
    public ArgusSpan status(SpanStatus status, String description) {
        span.setStatus(toOtel(status), description);
        return this;
    }

    @Override
    public String traceId() {
        return span.getSpanContext().isValid() ? span.getSpanContext().getTraceId() : "";
    }

    @Override
    public String spanId() {
        return span.getSpanContext().isValid() ? span.getSpanContext().getSpanId() : "";
    }

    @Override
    public void end() {
        if (ended.compareAndSet(false, true)) {
            scope.close();  // release active context BEFORE ending span
            span.end();
        }
    }

    @Override
    public void close() {
        end();
    }

    private static StatusCode toOtel(SpanStatus spanStatus) {
        return switch (spanStatus) {
            case OK    -> StatusCode.OK;
            case ERROR -> StatusCode.ERROR;
            case UNSET -> StatusCode.UNSET;
        };
    }
}
