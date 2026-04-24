package com.argus.tracer;

/** Semantic role of a span. Maps 1-to-1 to {@link io.opentelemetry.api.trace.SpanKind}. */
public enum SpanKind {
    /** Default — intra-service work with no network boundary. */
    INTERNAL,
    /** Inbound synchronous RPC or HTTP request handler. */
    SERVER,
    /** Outbound synchronous RPC or HTTP call to another service. */
    CLIENT,
    /** Publishing a message to a broker or queue. */
    PRODUCER,
    /** Consuming / processing a message from a broker or queue. */
    CONSUMER
}
