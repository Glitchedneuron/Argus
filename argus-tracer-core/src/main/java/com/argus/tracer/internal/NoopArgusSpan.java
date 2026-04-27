package com.argus.tracer.internal;

import com.argus.tracer.ArgusSpan;
import com.argus.tracer.SpanStatus;

/** No-op singleton — all operations are discarded with zero overhead. */
public final class NoopArgusSpan implements ArgusSpan {

    public static final NoopArgusSpan INSTANCE = new NoopArgusSpan();

    private NoopArgusSpan() {}

    @Override public ArgusSpan tag(String key, String value)                          { return this; }
    @Override public ArgusSpan tag(String key, long value)                            { return this; }
    @Override public ArgusSpan tag(String key, boolean value)                         { return this; }
    @Override public ArgusSpan recordException(Throwable throwable)                   { return this; }
    @Override public ArgusSpan status(SpanStatus spanStatus)                          { return this; }
    @Override public ArgusSpan status(SpanStatus spanStatus, String description)      { return this; }
    @Override public String traceId()                                                 { return ""; }
    @Override public String spanId()                                                  { return ""; }
    @Override public void end()                                                       {}
    @Override public void close()                                                     {}
}
