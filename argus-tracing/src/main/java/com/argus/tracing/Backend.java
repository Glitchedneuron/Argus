package com.argus.tracing;

/**
 * Tracing backend to use at runtime.
 *
 * <p>Selected via {@link ArgusTracerFactory.Builder#backend(Backend)}, the
 * {@code argus.tracer.backend} system property, or the {@code ARGUS_TRACER_BACKEND}
 * environment variable.  When none of the above is set the factory auto-detects.</p>
 */
public enum Backend {

    /**
     * OpenTelemetry SDK backend.
     *
     * <p>Exports spans via OTLP/HTTP when {@code OTEL_EXPORTER_OTLP_ENDPOINT} is set,
     * or writes JSON to stdout in dev mode when the endpoint is absent.</p>
     *
     * <p>Trace context is automatically propagated to {@code ArgusLogger} log records
     * because both use the same OTel {@code Context} — no extra wiring needed.</p>
     */
    OTEL,

    /**
     * Datadog native tracer backend (OpenTracing API).
     *
     * <p>Uses {@code io.opentracing.util.GlobalTracer} at runtime.  The GlobalTracer is
     * populated by:</p>
     * <ul>
     *   <li>Datadog Java agent ({@code dd-java-agent.jar}) — auto-registers on JVM startup.</li>
     *   <li>Programmatic registration via {@code com.datadoghq:dd-trace-java}.</li>
     * </ul>
     * <p>When neither is present the OpenTracing {@code NoopTracer} is used silently.</p>
     *
     * <p>Log-trace correlation with this backend requires the Datadog Java agent (which
     * injects {@code dd.trace_id}/{@code dd.span_id} into MDC automatically), or manual
     * injection via {@link ArgusSpan#traceId()} / {@link ArgusSpan#spanId()}.</p>
     */
    DATADOG,

    /**
     * No-op backend — discards all spans with zero overhead.
     *
     * <p>Useful for unit tests or when tracing is explicitly disabled.</p>
     */
    NOOP
}
