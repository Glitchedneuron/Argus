package com.argus.tracer;

import java.util.function.Supplier;

/**
 * Entry point for creating and managing distributed trace spans.
 *
 * <p>Obtain via {@link ArgusTracerFactory#create()} (env-var auto-detection)
 * or {@link ArgusTracerFactory#builder()} (explicit configuration).</p>
 *
 * <h2>Quick start</h2>
 * <pre>{@code
 * private static final ArgusTracer TRACER = ArgusTracerFactory.create();
 *
 * // Manual span
 * try (ArgusSpan span = TRACER.startSpan("process-order", SpanKind.SERVER)) {
 *     span.tag("order.id", orderId);
 *     processOrder(orderId);
 * }
 *
 * // Inline wrap — returns a value
 * Order order = TRACER.trace("fetch-order", () -> repo.findById(id));
 *
 * // Inline wrap — void
 * TRACER.trace("publish-event", () -> bus.publish(event));
 * }</pre>
 */
public interface ArgusTracer {

    // ---- Span creation -------------------------------------------------------

    /**
     * Start an {@link SpanKind#INTERNAL} span and make it active in the current thread.
     * The caller must end the span via try-with-resources or {@link ArgusSpan#end()}.
     */
    ArgusSpan startSpan(String spanName);

    /**
     * Start a span of the given {@link SpanKind} and make it active in the current thread.
     *
     * @param spanName human-readable operation name (e.g. {@code "db.query"})
     * @param kind     semantic role — {@code SERVER} for inbound, {@code CLIENT} for outbound
     */
    ArgusSpan startSpan(String spanName, SpanKind kind);

    // ---- Convenience wrappers ------------------------------------------------

    /**
     * Execute {@code block} inside an {@link SpanKind#INTERNAL} span and return its result.
     * The span is ended automatically. If {@code block} throws a {@link RuntimeException}
     * the span is marked {@link SpanStatus#ERROR} and the exception is re-thrown.
     */
    <T> T trace(String spanName, Supplier<T> block);

    /**
     * Execute {@code block} inside an {@link SpanKind#INTERNAL} span (void variant).
     * Same error-handling semantics as {@link #trace(String, Supplier)}.
     */
    void trace(String spanName, Runnable block);

    // ---- Active context ------------------------------------------------------

    /**
     * Returns the trace ID (32-hex W3C format) of the currently active span,
     * or an empty string when no span is active.
     */
    String currentTraceId();

    /**
     * Returns the span ID (16-hex W3C format) of the currently active span,
     * or an empty string when no span is active.
     */
    String currentSpanId();

    // ---- Lifecycle -----------------------------------------------------------

    /**
     * Flush pending spans and shut down the backend exporter.
     * Call once during application shutdown. After this call the tracer is unusable.
     */
    void shutdown();
}
