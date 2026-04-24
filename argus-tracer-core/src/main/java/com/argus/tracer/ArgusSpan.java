package com.argus.tracer;

/**
 * A single unit of distributed work tracked by the Argus tracing framework.
 *
 * <p>Obtain via {@link ArgusTracer#startSpan(String)} or
 * {@link ArgusTracer#startSpan(String, SpanKind)}.
 * Always end the span — prefer try-with-resources:</p>
 *
 * <pre>{@code
 * try (ArgusSpan span = tracer.startSpan("checkout", SpanKind.SERVER)) {
 *     span.tag("order.id", orderId)
 *         .tag("customer.tier", "gold");
 *     processOrder(orderId);
 * }
 * }</pre>
 *
 * <p>While a span is open it is set as the <em>active</em> span in the current
 * thread's OTel context, so any framework or library that reads
 * {@code Span.current()} (e.g. the OTel Java agent auto-instrumentation)
 * will automatically attach to this trace.</p>
 */
public interface ArgusSpan extends AutoCloseable {

    // ---- Attributes ----------------------------------------------------------

    /** Attach a string attribute. Returns {@code this} for chaining. */
    ArgusSpan tag(String key, String value);

    /** Attach a numeric attribute. Returns {@code this} for chaining. */
    ArgusSpan tag(String key, long value);

    /** Attach a boolean attribute. Returns {@code this} for chaining. */
    ArgusSpan tag(String key, boolean value);

    // ---- Errors --------------------------------------------------------------

    /**
     * Record an exception event on this span and mark its status as {@link SpanStatus#ERROR}.
     * Populates {@code exception.type}, {@code exception.message}, and
     * {@code exception.stacktrace} per OTel semantic conventions.
     *
     * @return {@code this} for chaining
     */
    ArgusSpan recordException(Throwable t);

    // ---- Status --------------------------------------------------------------

    /** Set the outcome status without a description. Returns {@code this}. */
    ArgusSpan setStatus(SpanStatus status);

    /**
     * Set the outcome status with a human-readable description.
     * The description is surfaced in back-end UIs (e.g. Datadog APM error message).
     *
     * @return {@code this} for chaining
     */
    ArgusSpan setStatus(SpanStatus status, String description);

    // ---- Context identifiers -------------------------------------------------

    /**
     * W3C trace ID — 32 lowercase hex characters.
     * Returns an empty string when the span is invalid (noop backend).
     */
    String traceId();

    /**
     * W3C span ID — 16 lowercase hex characters.
     * Returns an empty string when the span is invalid (noop backend).
     */
    String spanId();

    // ---- Lifecycle -----------------------------------------------------------

    /**
     * End the span, record its end-time, and release the active context scope.
     * Idempotent — safe to call multiple times.
     */
    void end();

    /**
     * Delegates to {@link #end()}.
     * Declared so try-with-resources works without a {@code catch} block.
     */
    @Override
    void close();
}
