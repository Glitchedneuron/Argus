package com.argus.tracer.internal;

import com.argus.tracer.ArgusSpan;
import com.argus.tracer.ArgusTracer;
import com.argus.tracer.SpanKind;

import java.util.function.Supplier;

/** No-op tracer — selected when {@link com.argus.tracer.Backend#NOOP} is active. */
public final class NoopTracerBackend implements ArgusTracer {

    @Override public ArgusSpan startSpan(String n)           { return NoopArgusSpan.INSTANCE; }
    @Override public ArgusSpan startSpan(String n, SpanKind k) { return NoopArgusSpan.INSTANCE; }
    @Override public <T> T trace(String n, Supplier<T> b)    { return b.get(); }
    @Override public void trace(String n, Runnable b)        { b.run(); }
    @Override public String currentTraceId()                 { return ""; }
    @Override public String currentSpanId()                  { return ""; }
    @Override public void shutdown()                         {}
}
