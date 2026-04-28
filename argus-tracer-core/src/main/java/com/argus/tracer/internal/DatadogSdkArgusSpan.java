package com.argus.tracer.internal;

import com.argus.tracer.ArgusSpan;
import com.argus.tracer.SpanStatus;
import io.opentracing.Scope;
import io.opentracing.Span;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** {@link ArgusSpan} backed by a Datadog (OpenTracing) {@link Span} + active {@link Scope}. */
final class DatadogSdkArgusSpan implements ArgusSpan {

    private final Span          span;
    private final Scope         scope;
    private final AtomicBoolean ended = new AtomicBoolean(false);

    DatadogSdkArgusSpan(Span span, Scope scope) {
        this.span  = span;
        this.scope = scope;
    }

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

    @Override
    public ArgusSpan recordException(Throwable throwable) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("event", "error");
        fields.put("error.object", throwable);
        fields.put("message", throwable.getMessage() != null ? throwable.getMessage() : "");
        fields.put("error.kind", throwable.getClass().getName());
        fields.put("stack", stackTrace(throwable));
        span.log(fields);
        span.setTag("error", true);
        return this;
    }

    @Override
    public ArgusSpan status(SpanStatus spanStatus) {
        if (spanStatus == SpanStatus.ERROR) {
            span.setTag("error", true);
        }
        return this;
    }

    @Override
    public ArgusSpan status(SpanStatus spanStatus, String description) {
        if (spanStatus == SpanStatus.ERROR) {
            span.setTag("error", true);
            span.setTag("error.message", description);
        }
        return this;
    }

    @Override
    public String traceId() {
        return span.context().toTraceId();
    }

    @Override
    public String spanId() {
        return span.context().toSpanId();
    }

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

    private static String stackTrace(Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
