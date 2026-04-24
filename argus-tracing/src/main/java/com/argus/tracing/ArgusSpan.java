package com.argus.tracing;

/**
 * A single unit of work tracked by the tracing framework.
 *
 * <p>Obtain via {@link ArgusTracer#startSpan(String)} or
 * {@link ArgusTracer#startSpan(String, SpanKind)}.  Always end the span — use
 * try-with-resources or call {@link #end()} explicitly:</p>
 *
 * <pre>{@code
 * try (ArgusSpan span = tracer.startSpan("validate-order")) {
 *     span.tag("order.id", orderId);
 *     processOrder(orderId);
 * }
 * }</pre>
 *
 * <p>Spans are set as the <em>active</em> span in the current thread's context when
 * created, so any {@code ArgusLogger.emit()} calls made while the span is open will
 * automatically carry the same {@code trace_id} and {@code span_id} (OTel backend).</p>
 *
 * <h2>Custom span &amp; trace IDs</h2>
 * <p>Use {@link #traceId()} and {@link #spanId()} to retrieve the IDs for manual log
 * correlation or propagation into downstream calls.</p>
 */
public interface ArgusSpan extends AutoCloseable {

    // ---- Attributes / tags --------------------------------------------------

    /** Attach a string attribute to this span. Returns {@code this} for chaining. */
    ArgusSpan tag(String key, String value);

    /** Attach a numeric attribute to this span. Returns {@code this} for chaining. */
    ArgusSpan tag(String key, long value);

    /** Attach a boolean attribute to this span. Returns {@code this} for chaining. */
    ArgusSpan tag(String key, boolean value);

    // ---- Error recording ----------------------------------------------------

    /**
     * Record an exception event on this span and mark it as errored.
     *
     * <p>Populates {@code exception.type}, {@code exception.message}, and
     * {@code exception.stacktrace} attributes following OTel semantic conventions.</p>
     *
     * @return {@code this} for chaining
     */
    ArgusSpan recordException(Throwable t);

    // ---- Status -------------------------------------------------------------

    /** Set the outcome status without a description message. Returns {@code this}. */
    ArgusSpan setStatus(SpanStatus status);

    /**
     * Set the outcome status with a human-readable description.
     * The description is surfaced as the status message in back-end UIs.
     *
     * @return {@code this} for chaining
     */
    ArgusSpan setStatus(SpanStatus status, String description);

    // ---- Context identifiers ------------------------------------------------

    /**
     * W3C-format trace ID (32 lowercase hex characters) for this span's trace.
     *
     * <p>Returns an empty string when the span is invalid (noop backend or span
     * context is not sampled).</p>
     */
    String traceId();

    /**
     * W3C-format span ID (16 lowercase hex characters) for this specific span.
     *
     * <p>Returns an empty string when the span is invalid.</p>
     */
    String spanId();

    // ---- Lifecycle ----------------------------------------------------------

    /**
     * End the span, record its end time, and release the active context scope.
     * Idempotent — safe to call multiple times.
     */
    void end();

    /**
     * Delegates to {@link #end()}.  Declared to suppress the checked-exception on
     * {@code AutoCloseable.close()} so try-with-resources works without a catch block.
     */
    @Override
    void close();
}
