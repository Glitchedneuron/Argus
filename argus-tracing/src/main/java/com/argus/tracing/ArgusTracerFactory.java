package com.argus.tracing;

import com.argus.tracing.internal.DatadogTracerBackend;
import com.argus.tracing.internal.NoopTracerBackend;
import com.argus.tracing.internal.OtelTracerBackend;
import io.opentelemetry.api.OpenTelemetry;

/**
 * Factory for obtaining {@link ArgusTracer} instances.
 *
 * <h2>Quickstart (env-var driven)</h2>
 * <pre>{@code
 * private static final ArgusTracer TRACER = ArgusTracerFactory.create();
 * }</pre>
 *
 * <h2>Explicit configuration</h2>
 * <pre>{@code
 * ArgusTracer tracer = ArgusTracerFactory.builder()
 *         .backend(Backend.OTEL)
 *         .serviceName("order-service")
 *         .serviceVersion("2.0.0")
 *         .environment("production")
 *         .build();
 * }</pre>
 *
 * <h2>Shared OTel SDK instance</h2>
 * <p>Pass the same {@code OpenTelemetry} instance used by {@code ArgusLoggerFactory} so
 * both logging and tracing share a single SDK (single resource, single exporter pipeline):</p>
 * <pre>{@code
 * OpenTelemetry otel = buildSharedOtelSdk();
 * ArgusLogger  logger = ArgusLoggerFactory.builder().withOpenTelemetry(otel).build()
 *                                          .getLogger(MyService.class.getName());
 * ArgusTracer  tracer = ArgusTracerFactory.builder().withOpenTelemetry(otel).build();
 * }</pre>
 *
 * <h2>Backend auto-detection order</h2>
 * <ol>
 *   <li>{@link Builder#backend(Backend)} — explicit programmatic override</li>
 *   <li>{@code argus.tracer.backend} system property</li>
 *   <li>{@code ARGUS_TRACER_BACKEND} environment variable</li>
 *   <li>{@code OTEL_EXPORTER_OTLP_ENDPOINT} set → {@link Backend#OTEL}</li>
 *   <li>{@code DD_AGENT_HOST} or {@code DD_TRACE_AGENT_URL} set → {@link Backend#DATADOG}</li>
 *   <li>Fallback → {@link Backend#OTEL} (console output in dev mode)</li>
 * </ol>
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

        private Backend     backend;
        private String      serviceName;
        private String      serviceVersion;
        private String      environment;
        private String      exporterEndpoint;
        private OpenTelemetry openTelemetry;

        /**
         * Explicitly select the backend.
         * When set, system-property and env-var auto-detection is skipped.
         */
        public Builder backend(Backend backend) {
            this.backend = backend;
            return this;
        }

        /** Override {@code OTEL_SERVICE_NAME}. */
        public Builder serviceName(String serviceName) {
            this.serviceName = serviceName;
            return this;
        }

        /** Override {@code OTEL_SERVICE_VERSION}. */
        public Builder serviceVersion(String serviceVersion) {
            this.serviceVersion = serviceVersion;
            return this;
        }

        /** Override {@code DEPLOYMENT_ENVIRONMENT}. */
        public Builder environment(String environment) {
            this.environment = environment;
            return this;
        }

        /**
         * Override the OTLP exporter endpoint for the OTel backend.
         * Falls back to {@code OTEL_EXPORTER_OTLP_ENDPOINT} env var, then console.
         */
        public Builder exporterEndpoint(String url) {
            this.exporterEndpoint = url;
            return this;
        }

        /**
         * Use an existing {@link OpenTelemetry} instance (OTel backend only).
         *
         * <p>When set, the factory delegates tracer creation to this instance instead
         * of building its own {@code SdkTracerProvider}.  Useful when the application
         * already manages a full OTel SDK (e.g. via the Java agent or a shared bootstrap).</p>
         *
         * <p>{@link #serviceName}, {@link #serviceVersion}, {@link #environment}, and
         * {@link #exporterEndpoint} are ignored when this is set, because the resource
         * and exporter pipeline are owned by the supplied instance.</p>
         */
        public Builder withOpenTelemetry(OpenTelemetry openTelemetry) {
            this.openTelemetry = openTelemetry;
            return this;
        }

        public ArgusTracer build() {
            Backend resolved = resolveBackend();
            TracerConfig config = new TracerConfig(
                    resolved, serviceName, serviceVersion, environment, exporterEndpoint);
            return switch (resolved) {
                case OTEL     -> new OtelTracerBackend(config, openTelemetry);
                case DATADOG  -> new DatadogTracerBackend(config);
                case NOOP     -> new NoopTracerBackend();
            };
        }

        // ---- Backend resolution -------------------------------------------------

        private Backend resolveBackend() {
            if (backend != null) return backend;

            String prop = System.getProperty("argus.tracer.backend");
            if (prop != null && !prop.isBlank()) return parseBackend(prop);

            String envVar = System.getenv("ARGUS_TRACER_BACKEND");
            if (envVar != null && !envVar.isBlank()) return parseBackend(envVar);

            // Auto-detect from well-known environment variables
            if (System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT") != null) return Backend.OTEL;
            if (System.getenv("DD_AGENT_HOST") != null
                    || System.getenv("DD_TRACE_AGENT_URL") != null) return Backend.DATADOG;

            // Default: OTel with console output (dev-friendly)
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
