package com.argus.tracer;

/** Selects which tracing backend the SDK is wired to. */
public enum Backend {

    /** OpenTelemetry SDK → OTLP/HTTP to {@code OTEL_EXPORTER_OTLP_ENDPOINT}, or JSON console in dev. */
    OTEL,

    /** OTel SDK → OTLP/HTTP to the Datadog agent at {@code DD_AGENT_HOST:4318}.
     *  Adds Datadog Unified Service Tagging resource attributes ({@code dd.service / dd.env / dd.version}). */
    DATADOG,

    /**
     * dd-trace-java native tracer → Datadog agent native protocol at {@code DD_AGENT_HOST:8126}.
     * Uses the official Datadog SDK (dd-trace-ot) directly; no OTLP ingestion required.
     * Set {@code ARGUS_TRACER_BACKEND=datadog_native} to select.
     */
    DATADOG_NATIVE,

    /** Discards all spans with zero overhead. */
    NOOP
}
