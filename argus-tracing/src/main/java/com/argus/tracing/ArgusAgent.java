package com.argus.tracing;

import com.argus.logging.ArgusLogger;
import com.argus.logging.ArgusLoggerFactory;
import com.argus.tracing.internal.NoopTracerBackend;
import com.argus.tracing.internal.OtelTracerBackend;
import com.argus.tracing.internal.UnifiedSdkInitializer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;

/**
 * Central lifecycle manager for the Argus observability framework.
 *
 * <p>{@code ArgusAgent} is the recommended entry point when you need both
 * <strong>structured logging</strong> ({@link ArgusLogger}) and
 * <strong>distributed tracing</strong> ({@link ArgusTracer}) in the same application.
 * It owns a single {@code OpenTelemetrySdk} instance shared by both, which means:</p>
 * <ul>
 *   <li>Logging and tracing share the same Resource (service.name, version, env).</li>
 *   <li>Log records emitted while an {@code ArgusTracer} span is active automatically
 *       carry the matching {@code trace_id} / {@code span_id} — zero manual wiring.</li>
 *   <li>A single {@link #shutdown()} call flushes and closes all exporters.</li>
 * </ul>
 *
 * <h2>Quick start</h2>
 * <pre>{@code
 * ArgusAgent agent = ArgusAgent.builder()
 *         .serviceName("order-service")
 *         .serviceVersion("2.0.0")
 *         .environment("production")
 *         .build();  // backend auto-detected from env vars
 *
 * private static final ArgusTracer TRACER = agent.tracer();
 * private static final ArgusLogger LOG    = agent.logger(OrderService.class.getName());
 *
 * // At JVM exit:
 * Runtime.getRuntime().addShutdownHook(new Thread(agent::shutdown));
 * }</pre>
 *
 * <h2>Backend selection</h2>
 * <table border="1">
 *   <tr><th>Backend</th><th>Sends to</th><th>Selection</th></tr>
 *   <tr>
 *     <td>{@link Backend#OTEL}</td>
 *     <td>OTel Collector / Datadog agent OTLP port (4317/4318)</td>
 *     <td>{@code OTEL_EXPORTER_OTLP_ENDPOINT} set, or default</td>
 *   </tr>
 *   <tr>
 *     <td>{@link Backend#DATADOG}</td>
 *     <td>Datadog agent OTLP ingestion ({@code DD_AGENT_HOST:4318})</td>
 *     <td>{@code DD_AGENT_HOST} env var set</td>
 *   </tr>
 *   <tr>
 *     <td>{@link Backend#NOOP}</td>
 *     <td>–</td>
 *     <td>Explicit only</td>
 *   </tr>
 * </table>
 *
 * <p>Both {@code OTEL} and {@code DATADOG} backends use the OTel SDK internally.
 * The difference is the OTLP endpoint and, for Datadog, extra resource attributes
 * ({@code dd.service}, {@code dd.env}, {@code dd.version}) required for
 * <a href="https://docs.datadoghq.com/getting_started/tagging/unified_service_tagging/">
 * Datadog Unified Service Tagging</a>.</p>
 *
 * <h2>Bring your own SDK</h2>
 * <p>If the application already manages an {@code OpenTelemetry} instance (e.g. via the
 * OTel Java agent), pass it via {@link Builder#withOpenTelemetry(OpenTelemetry)} and the
 * agent will use it instead of creating its own:</p>
 * <pre>{@code
 * ArgusAgent agent = ArgusAgent.builder()
 *         .withOpenTelemetry(GlobalOpenTelemetry.get())
 *         .build();
 * }</pre>
 */
public final class ArgusAgent {

    private final ArgusTracer        tracer;
    private final ArgusLoggerFactory loggerFactory;
    /** Non-null only when this agent owns the SDK (i.e. no external OTel was supplied). */
    private final OpenTelemetrySdk   ownedSdk;

    private ArgusAgent(ArgusTracer tracer, ArgusLoggerFactory loggerFactory, OpenTelemetrySdk ownedSdk) {
        this.tracer        = tracer;
        this.loggerFactory = loggerFactory;
        this.ownedSdk      = ownedSdk;
    }

    // ---- Public API ---------------------------------------------------------

    /** Returns the {@link ArgusTracer} backed by this agent's SDK. */
    public ArgusTracer tracer() {
        return tracer;
    }

    /** Returns the {@link ArgusLoggerFactory} backed by this agent's SDK. */
    public ArgusLoggerFactory loggerFactory() {
        return loggerFactory;
    }

    /**
     * Convenience shorthand for {@code loggerFactory().getLogger(name)}.
     *
     * @param name typically {@code MyClass.class.getName()}
     */
    public ArgusLogger logger(String name) {
        return loggerFactory.getLogger(name);
    }

    /**
     * Flush all pending spans and log records, then shut down the SDK.
     *
     * <p>Call once during application shutdown, e.g. via a JVM shutdown hook.
     * After this call the tracer and loggers are unusable.</p>
     */
    public void shutdown() {
        tracer.shutdown();  // flushes owned SdkTracerProvider (if any)
        if (ownedSdk != null) {
            ownedSdk.close();
        }
    }

    // ---- Factory ------------------------------------------------------------

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

        /**
         * Explicitly select the backend.
         * When set, env-var auto-detection is bypassed.
         */
        public Builder backend(Backend backend) {
            this.backend = backend;
            return this;
        }

        public Builder serviceName(String serviceName) {
            this.serviceName = serviceName;
            return this;
        }

        public Builder serviceVersion(String serviceVersion) {
            this.serviceVersion = serviceVersion;
            return this;
        }

        public Builder environment(String environment) {
            this.environment = environment;
            return this;
        }

        /**
         * Override the OTLP endpoint for the OTel backend.
         * For the Datadog backend use {@link #datadogAgentHost(String)} instead.
         */
        public Builder exporterEndpoint(String url) {
            this.exporterEndpoint = url;
            return this;
        }

        /**
         * Hostname of the Datadog agent for the {@link Backend#DATADOG} backend.
         * Defaults to {@code DD_AGENT_HOST} env var, then {@code localhost}.
         * The agent must have OTLP/HTTP ingestion enabled on port 4318.
         */
        public Builder datadogAgentHost(String host) {
            this.datadogAgentHost = host;
            return this;
        }

        /**
         * Use an existing {@link OpenTelemetry} instance (e.g. from the OTel Java agent
         * or a shared bootstrap class) instead of creating a new SDK.
         *
         * <p>When set, all other builder options except {@link #backend(Backend)} are
         * ignored — the provided instance's resource, exporters, and configuration are
         * used as-is.</p>
         */
        public Builder withOpenTelemetry(OpenTelemetry openTelemetry) {
            this.openTelemetry = openTelemetry;
            return this;
        }

        public ArgusAgent build() {
            Backend resolved = resolveBackend();

            if (resolved == Backend.NOOP) {
                return new ArgusAgent(
                        new NoopTracerBackend(),
                        ArgusLoggerFactory.create(),   // standalone logging (no tracing needed)
                        null);
            }

            // For OTEL and DATADOG: build (or reuse) a unified OTel SDK instance.
            OpenTelemetrySdk sdk;
            boolean          ownsSdk;

            if (openTelemetry instanceof OpenTelemetrySdk externalSdk) {
                sdk     = externalSdk;
                ownsSdk = false;
            } else if (openTelemetry != null) {
                // Non-SDK OpenTelemetry instance (e.g. GlobalOpenTelemetry from agent).
                // We can't own its lifecycle; wrap it.
                ArgusTracer        tracer  = new OtelTracerBackend(null, openTelemetry);
                ArgusLoggerFactory factory = ArgusLoggerFactory.builder()
                        .withOpenTelemetry(openTelemetry).build();
                return new ArgusAgent(tracer, factory, null);
            } else {
                // Build our own unified SDK (the typical path).
                sdk     = UnifiedSdkInitializer.initialize(
                        resolved, serviceName, serviceVersion, environment,
                        exporterEndpoint, datadogAgentHost);
                ownsSdk = true;
            }

            ArgusTracer        tracer  = new OtelTracerBackend(null, sdk);
            ArgusLoggerFactory factory = ArgusLoggerFactory.builder()
                    .withOpenTelemetry(sdk).build();

            return new ArgusAgent(tracer, factory, ownsSdk ? sdk : null);
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

            return Backend.OTEL;  // default: OTel with console in dev
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
