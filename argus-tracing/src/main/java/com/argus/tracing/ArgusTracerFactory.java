package com.argus.tracing;

import com.argus.tracing.internal.NoopTracerBackend;
import com.argus.tracing.internal.OtelTracerBackend;
import io.opentelemetry.api.OpenTelemetry;

/**
 * Factory for obtaining standalone {@link ArgusTracer} instances.
 *
 * <p>For most use-cases prefer {@link ArgusAgent}, which provides both a tracer and a
 * logger factory backed by a single shared {@code OpenTelemetrySdk}.  Use this factory
 * only when you need a tracer without the full agent lifecycle.</p>
 *
 * <h2>Backend selection order</h2>
 * <ol>
 *   <li>{@link Builder#backend(Backend)} — explicit programmatic override</li>
 *   <li>{@code argus.tracer.backend} system property</li>
 *   <li>{@code ARGUS_TRACER_BACKEND} environment variable</li>
 *   <li>{@code DD_AGENT_HOST} / {@code DD_TRACE_AGENT_URL} set → {@link Backend#DATADOG}
 *       (OTLP to Datadog agent at {@code DD_AGENT_HOST:4318})</li>
 *   <li>{@code OTEL_EXPORTER_OTLP_ENDPOINT} set → {@link Backend#OTEL}</li>
 *   <li>Fallback → {@link Backend#OTEL} with console output (dev mode)</li>
 * </ol>
 *
 * <p>Both {@link Backend#OTEL} and {@link Backend#DATADOG} use the OpenTelemetry SDK
 * internally.  The Datadog backend configures the SDK to send OTLP to the Datadog
 * agent's OTLP ingestion port (4318) and adds Datadog Unified Service Tagging resource
 * attributes ({@code dd.service}, {@code dd.env}, {@code dd.version}).</p>
 */
public final class ArgusTracerFactory {

    private ArgusTracerFactory() {}

    /**
     * Create a tracer using environment-variable-driven auto-detection.
     * Equivalent to {@code ArgusTracerFactory.builder().build()}.
     */
    public static ArgusTracer create() {
        return builder().build();
    }

    /** Returns a builder for explicit configuration. */
    public static Builder builder() {
        return new Builder();
    }

    // -------------------------------------------------------------------------

    public static final class Builder {

        private Backend       backend;
        private String        serviceName;
        private String        serviceVersion;
        private String        environment;
        private String        exporterEndpoint;
        private OpenTelemetry openTelemetry;

        public Builder backend(Backend backend)             { this.backend = backend; return this; }
        public Builder serviceName(String n)                { this.serviceName = n; return this; }
        public Builder serviceVersion(String v)             { this.serviceVersion = v; return this; }
        public Builder environment(String e)                { this.environment = e; return this; }
        public Builder exporterEndpoint(String url)         { this.exporterEndpoint = url; return this; }

        /**
         * Use an existing {@link OpenTelemetry} instance.
         * When set, the factory creates no SDK of its own — {@link #serviceName},
         * {@link #serviceVersion}, {@link #environment}, and {@link #exporterEndpoint}
         * are ignored.
         */
        public Builder withOpenTelemetry(OpenTelemetry ot)  { this.openTelemetry = ot; return this; }

        public ArgusTracer build() {
            Backend resolved = resolveBackend();
            if (resolved == Backend.NOOP) return new NoopTracerBackend();

            // Both OTEL and DATADOG route through OtelTracerBackend.
            // TracerConfig carries the backend enum so OtelTracingInitializer
            // can select the right OTLP endpoint and resource attributes.
            TracerConfig config = new TracerConfig(
                    resolved, serviceName, serviceVersion, environment, exporterEndpoint);
            return new OtelTracerBackend(config, openTelemetry);
        }

        // ---- Backend resolution -----------------------------------------------

        private Backend resolveBackend() {
            if (backend != null) return backend;

            String prop = System.getProperty("argus.tracer.backend");
            if (prop != null && !prop.isBlank()) return parseBackend(prop);

            String envVar = System.getenv("ARGUS_TRACER_BACKEND");
            if (envVar != null && !envVar.isBlank()) return parseBackend(envVar);

            if (System.getenv("DD_AGENT_HOST") != null
                    || System.getenv("DD_TRACE_AGENT_URL") != null) return Backend.DATADOG;
            if (System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT") != null) return Backend.OTEL;

            return Backend.OTEL;
        }

        private static Backend parseBackend(String value) {
            return switch (value.trim().toUpperCase()) {
                case "OTEL", "OPENTELEMETRY" -> Backend.OTEL;
                case "DATADOG", "DD"          -> Backend.DATADOG;
                case "NOOP", "NONE", "OFF"    -> Backend.NOOP;
                default -> throw new IllegalArgumentException(
                        "Unknown argus.tracer.backend value: \"" + value
                        + "\". Valid values: otel, datadog, noop.");
            };
        }
    }
}
