package com.argus.tracing;

/**
 * High-level outcome of a span.
 *
 * <p>Maps to {@code io.opentelemetry.api.trace.StatusCode} for the OTel backend and to
 * the {@code error} boolean tag for the Datadog backend.</p>
 */
public enum SpanStatus {

    /** The operation completed successfully. Renders as {@code StatusCode.OK} in OTel. */
    OK,

    /** The operation failed.  Renders as {@code StatusCode.ERROR} and sets {@code error=true} in Datadog. */
    ERROR,

    /**
     * No explicit status set (the default).
     * Backends treat this as success unless an exception was recorded.
     */
    UNSET
}
