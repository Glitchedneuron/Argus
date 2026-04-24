package com.argus.tracer.internal;

import com.argus.tracer.ArgusSpan;
import com.argus.tracer.SpanStatus;

/** No-op singleton — all operations are discarded with zero overhead. */
public final class NoopArgusSpan implements ArgusSpan {

    public static final NoopArgusSpan INSTANCE = new NoopArgusSpan();

    private NoopArgusSpan() {}

    @Override public ArgusSpan tag(String k, String v)          { return this; }
    @Override public ArgusSpan tag(String k, long v)            { return this; }
    @Override public ArgusSpan tag(String k, boolean v)         { return this; }
    @Override public ArgusSpan recordException(Throwable t)     { return this; }
    @Override public ArgusSpan setStatus(SpanStatus s)          { return this; }
    @Override public ArgusSpan setStatus(SpanStatus s, String d){ return this; }
    @Override public String traceId()                           { return ""; }
    @Override public String spanId()                            { return ""; }
    @Override public void end()                                 {}
    @Override public void close()                               {}
}
