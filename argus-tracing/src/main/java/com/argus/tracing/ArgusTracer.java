package com.argus.tracing;

import java.util.function.Supplier;

/**
 * Entry point for creating and managing distributed trace spans.
 *
 * <p>Obtain via {@link ArgusTracerFactory#create()} or
 * {@link ArgusTracerFactory#builder()}.</p>
 *
 * <h2>Quickstart</h2>
 * <pre>{@code
 * private static final ArgusTracer TRACER = ArgusTracerFactory.create();
 *
 * // Manual span (try-with-resources)
 * try (ArgusSpan span = TRACER.startSpan("process-order", SpanKind.SERVER)) {
 *     span.tag("order.id", orderId);
 *     processOrder(orderId);
 * }
 *
 * // Inline wrap
 * TRACER.trace("validate-payment", () -> paymentService.validate(payment));
 * }</pre>
 *
 * <h2>Log correlation</h2>
 * <p>When the OTel backend is active, any {@code ArgusLogger.emit()} call made while a
 * span is open automatically carries the same {@code trace_id} and {@code span_id} —
 * zero extra wiring required.</p>
 */
public interface ArgusTracer {

    // ---- Span creation ------------------------------------------------------

    /**
     * Start an {@link SpanKind#INTERNAL} span with the given name and make it the
     * active span in the current thread's context.
     *
     * <p>The caller is responsible for ending the span via try-with-resources or an
     * explicit {@link ArgusSpan#end()} call.</p>
     */
    ArgusSpan startSpan(String spanName);

    /**
     * Start a span of the specified {@link SpanKind} and make it active.
     *
     * @param spanName human-readable operation name (e.g. {@code "db.query"})
     * @param kind     semantic role of the span
     */
    ArgusSpan startSpan(String spanName, SpanKind kind);

    // ---- Convenience wrappers -----------------------------------------------

    /**
     * Execute {@code block} inside an {@link SpanKind#INTERNAL} span.
     *
     * <p>The span is ended automatically after the block returns.  If the block throws
     * a {@link RuntimeException} the span is marked {@link SpanStatus#ERROR} and the
     * exception is re-thrown.</p>
     *
     * @param spanName operation name
     * @param block    code to execute
     * @param <T>      return type
     * @return the value returned by {@code block}
     */
    <T> T trace(String spanName, Supplier<T> block);

    /**
     * Execute {@code block} inside an {@link SpanKind#INTERNAL} span.
     * Same semantics as {@link #trace(String, Supplier)} for void operations.
     */
    void trace(String spanName, Runnable block);

    // ---- Active context -----------------------------------------------------

    /**
     * Returns the trace ID (32-hex) of the currently active span, or an empty string
     * when no span is active.
     *
     * <p>Useful for attaching the trace ID to outbound headers or log fields manually.</p>
     */
    String currentTraceId();

    /**
     * Returns the span ID (16-hex) of the currently active span, or an empty string
     * when no span is active.
     */
    String currentSpanId();

    // ---- Lifecycle ----------------------------------------------------------

    /**
     * Flush pending spans and shut down the backend's exporter.
     *
     * <p>Call once during application shutdown.  After this call the tracer is unusable.</p>
     */
    void shutdown();
}
