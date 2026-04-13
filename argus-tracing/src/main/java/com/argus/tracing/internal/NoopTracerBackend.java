package com.argus.tracing.internal;

import com.argus.tracing.ArgusSpan;
import com.argus.tracing.ArgusTracer;
import com.argus.tracing.SpanKind;

import java.util.function.Supplier;

/**
 * No-op {@link ArgusTracer} — zero overhead; all spans are discarded immediately.
 *
 * <p>Selected when {@link com.argus.tracing.Backend#NOOP} is configured, or useful as
 * a drop-in stub in unit tests where tracing is irrelevant.</p>
 */
public final class NoopTracerBackend implements ArgusTracer {

    @Override
    public ArgusSpan startSpan(String spanName)                    { return NoopArgusSpan.INSTANCE; }

    @Override
    public ArgusSpan startSpan(String spanName, SpanKind kind)     { return NoopArgusSpan.INSTANCE; }

    @Override
    public <T> T trace(String spanName, Supplier<T> block)         { return block.get(); }

    @Override
    public void trace(String spanName, Runnable block)             { block.run(); }

    @Override
    public String currentTraceId()                                 { return ""; }

    @Override
    public String currentSpanId()                                  { return ""; }

    @Override
    public void shutdown()                                         {}
}
