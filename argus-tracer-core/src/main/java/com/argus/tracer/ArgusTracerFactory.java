package com.argus.tracer;

import com.argus.tracer.internal.DatadogSdkInitializer;
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
 * automatically wraps the backend the agent configured — OTel, Datadog OTLP, or Datadog native.
 * No extra wiring needed.</p>
 *
 * <h2>Explicit configuration</h2>
 * <pre>{@code
 * ArgusTracer tracer = ArgusTracerFactory.builder()
 *         .backend(Backend.DATADOG_NATIVE)
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

    private static final String PROP_INITIALIZED = "argus.tracer.initialized";
    private static final String PROP_BACKEND     = "argus.tracer.backend";
    private static final String BACKEND_DD_NATIVE = "datadog_native";

    private ArgusTracerFactory() {}

    /**
     * Create a tracer using environment-variable auto-detection.
     *
     * <p>When the Argus tracing agent is attached ({@code -javaagent:argus-tracer-agent.jar}),
     * this method wraps the backend the agent configured. For the OTel/DATADOG backends it wraps
     * {@code GlobalOpenTelemetry}; for {@link Backend#DATADOG_NATIVE} it wraps the DD global tracer.</p>
     */
    public static ArgusTracer create() {
        if ("true".equals(System.getProperty(PROP_INITIALIZED))) {
            if (BACKEND_DD_NATIVE.equals(System.getProperty(PROP_BACKEND))) {
                return DatadogSdkInitializer.initialize(null, null, null, null, 0);
            }
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
        public Builder backend(Backend backend)              { this.backend = backend; return this; }
        public Builder serviceName(String name)              { this.serviceName = name; return this; }
        public Builder serviceVersion(String version)        { this.serviceVersion = version; return this; }
        public Builder environment(String environment)       { this.environment = environment; return this; }

        /** Override the OTLP endpoint (OTel / DATADOG backends). Use {@link #datadogAgentHost} for Datadog native. */
        public Builder exporterEndpoint(String url)          { this.exporterEndpoint = url; return this; }

        /** Datadog agent hostname. Defaults to {@code DD_AGENT_HOST} env var, then {@code localhost}. */
        public Builder datadogAgentHost(String host)         { this.datadogAgentHost = host; return this; }

        /**
         * Supply an existing {@link OpenTelemetry} instance (e.g. from the OTel Java agent).
         * When set, all other options except {@link #backend} are ignored.
         * Not applicable for {@link Backend#DATADOG_NATIVE}.
         */
        public Builder withOpenTelemetry(OpenTelemetry openTelemetry) { this.openTelemetry = openTelemetry; return this; }

        public ArgusTracer build() {
            Backend resolved = resolveBackend();
            if (resolved == Backend.NOOP) return new NoopTracerBackend();

            if (resolved == Backend.DATADOG_NATIVE) {
                return DatadogSdkInitializer.initialize(
                        serviceName, serviceVersion, environment, datadogAgentHost, 0);
            }

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

            String prop = System.getProperty(PROP_BACKEND);
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
                case "OTEL", "OPENTELEMETRY"         -> Backend.OTEL;
                case "DATADOG", "DD"                 -> Backend.DATADOG;
                case "DATADOG_NATIVE", "DD_NATIVE"   -> Backend.DATADOG_NATIVE;
                case "NOOP", "NONE", "OFF"           -> Backend.NOOP;
                default -> throw new IllegalArgumentException(
                        "Unknown argus.tracer.backend: \"" + value
                        + "\". Valid values: otel, datadog, datadog_native, noop");
            };
        }
    }
}
