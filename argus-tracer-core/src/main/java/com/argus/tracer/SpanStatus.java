package com.argus.tracer;

/** Final outcome of a span. Maps to the OTel {@code StatusCode} enum. */
public enum SpanStatus {
    /** Operation completed successfully. */
    OK,
    /** Operation failed. Shown as an error in back-end UIs. */
    ERROR,
    /** No explicit status set (default). */
    UNSET
}
