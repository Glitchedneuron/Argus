package com.argus.tracing.internal;

import com.argus.tracing.ArgusSpan;
import com.argus.tracing.SpanStatus;

/**
 * No-op {@link ArgusSpan} — all methods are no-ops; all ID accessors return {@code ""}.
 *
 * <p>Used by {@link NoopTracerBackend} and returned by {@link OtelTracerBackend} /
 * {@link DatadogTracerBackend} when the underlying SDK produces an invalid span.</p>
 */
final class NoopArgusSpan implements ArgusSpan {

    static final NoopArgusSpan INSTANCE = new NoopArgusSpan();

    private NoopArgusSpan() {}

    @Override public ArgusSpan tag(String key, String value)                  { return this; }
    @Override public ArgusSpan tag(String key, long value)                    { return this; }
    @Override public ArgusSpan tag(String key, boolean value)                 { return this; }
    @Override public ArgusSpan recordException(Throwable t)                   { return this; }
    @Override public ArgusSpan setStatus(SpanStatus status)                   { return this; }
    @Override public ArgusSpan setStatus(SpanStatus status, String description){ return this; }
    @Override public String    traceId()                                       { return ""; }
    @Override public String    spanId()                                        { return ""; }
    @Override public void      end()                                           {}
    @Override public void      close()                                         {}
}
