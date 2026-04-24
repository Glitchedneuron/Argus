package com.argus.tracer;

import com.argus.tracer.internal.NoopTracerBackend;
import com.argus.tracer.internal.OtelTracerBackend;
import com.argus.tracer.internal.SdkInitializer;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;

/**
 * Factory for obtaining {@link ArgusTracer} instances.
 *
 * <h2>Auto-detection (recommended)</h2>
 * <pre>{@code
 * ArgusTracer tracer = ArgusTracerFactory.create();
 * }</pre>
 *
 * <p>When {@code argus-tracer-agent.jar} is attached as {@code -javaagent}, {@code create()}
 * automatically wraps {@code GlobalOpenTelemetry} — the same SDK the agent configured.
 * No extra wiring needed.</p>
 *
 * <h2>Explicit configuration</h2>
 * <pre>{@code
 * ArgusTracer tracer = ArgusTracerFactory.builder()
 *         .backend(Backend.DATADOG)
 *         .serviceName("order-service")
 *         .serviceVersion("2.0.0")
 *         .environment("production")
 *         .datadogAgentHost("datadog-agent.svc.cluster.local")
 *         .build();
 *
 * Runtime.getRuntime().addShutdownHook(new Thread(tracer::shutdown));
 * }</pre>
 *
 * <h2>Backend auto-detection order</h2>
 * <ol>
 *   <li>{@link Builder#backend(Backend)} — explicit override</li>
 *   <li>{@code argus.tracer.backend} system property</li>
 *   <li>{@code ARGUS_TRACER_BACKEND} environment variable</li>
 *   <li>{@code DD_AGENT_HOST} or {@code DD_TRACE_AGENT_URL} set → {@link Backend#DATADOG}</li>
 *   <li>{@code OTEL_EXPORTER_OTLP_ENDPOINT} set → {@link Backend#OTEL}</li>
 *   <li>Fallback → {@link Backend#OTEL} with JSON console output (dev mode)</li>
 * </ol>
 */
public final class ArgusTracerFactory {

    private ArgusTracerFactory() {}

    /**
     * Create a tracer using environment-variable auto-detection.
     *
     * <p>When the Argus tracing agent is attached ({@code -javaagent:argus-tracer-agent.jar}),
     * this method wraps {@code GlobalOpenTelemetry} so your custom spans join the same
     * trace as any spans the agent or other OTel instrumentation creates.</p>
     */
    public static ArgusTracer create() {
        if ("true".equals(System.getProperty("argus.tracer.initialized"))) {
            return new OtelTracerBackend(GlobalOpenTelemetry.get(), null);
        }
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
        private String        datadogAgentHost;
        private OpenTelemetry openTelemetry;

        /** Explicitly select the backend. Bypasses all env-var auto-detection. */
        public Builder backend(Backend b)              { this.backend = b; return this; }
        public Builder serviceName(String n)            { this.serviceName = n; return this; }
        public Builder serviceVersion(String v)         { this.serviceVersion = v; return this; }
        public Builder environment(String e)            { this.environment = e; return this; }

        /** Override the OTLP endpoint (OTel backend). Use {@link #datadogAgentHost} for Datadog. */
        public Builder exporterEndpoint(String url)     { this.exporterEndpoint = url; return this; }

        /** Datadog agent hostname. Defaults to {@code DD_AGENT_HOST} env var, then {@code localhost}. */
        public Builder datadogAgentHost(String h)       { this.datadogAgentHost = h; return this; }

        /**
         * Supply an existing {@link OpenTelemetry} instance (e.g. from the OTel Java agent).
         * When set, all other options except {@link #backend} are ignored.
         */
        public Builder withOpenTelemetry(OpenTelemetry ot) { this.openTelemetry = ot; return this; }

        public ArgusTracer build() {
            Backend resolved = resolveBackend();
            if (resolved == Backend.NOOP) return new NoopTracerBackend();

            if (openTelemetry != null) {
                return new OtelTracerBackend(openTelemetry, null);
            }

            OpenTelemetrySdk sdk = SdkInitializer.initialize(
                    resolved, serviceName, serviceVersion, environment,
                    exporterEndpoint, datadogAgentHost);
            return new OtelTracerBackend(sdk, sdk);
        }

        private Backend resolveBackend() {
            if (backend != null) return backend;

            String prop = System.getProperty("argus.tracer.backend");
            if (prop != null && !prop.isBlank()) return parseBackend(prop);

            String env = System.getenv("ARGUS_TRACER_BACKEND");
            if (env != null && !env.isBlank()) return parseBackend(env);

            if (System.getenv("DD_AGENT_HOST") != null
                    || System.getenv("DD_TRACE_AGENT_URL") != null) return Backend.DATADOG;
            if (System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT") != null) return Backend.OTEL;

            return Backend.OTEL;
        }

        private static Backend parseBackend(String value) {
            return switch (value.trim().toUpperCase()) {
                case "OTEL", "OPENTELEMETRY" -> Backend.OTEL;
                case "DATADOG", "DD"         -> Backend.DATADOG;
                case "NOOP", "NONE", "OFF"   -> Backend.NOOP;
                default -> throw new IllegalArgumentException(
                        "Unknown argus.tracer.backend: \"" + value
                        + "\". Valid values: otel, datadog, noop");
            };
        }
    }
}
