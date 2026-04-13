package com.argus.tracing;

/**
 * Semantic role of a span — mirrors the OTel {@code SpanKind} enumeration.
 *
 * <p>For the OTel backend these values map 1-to-1 to
 * {@code io.opentelemetry.api.trace.SpanKind}.</p>
 * <p>For the Datadog backend the kind is set as the {@code span.kind} tag.</p>
 */
public enum SpanKind {

    /** Default — an internal operation not crossing a process boundary. */
    INTERNAL,

    /** A synchronous inbound request handled by this service. */
    SERVER,

    /** A synchronous outbound request made by this service. */
    CLIENT,

    /** An outbound message sent to a message broker. */
    PRODUCER,

    /** An inbound message received from a message broker. */
    CONSUMER
}
