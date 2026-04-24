package com.argus.agent;

import com.argus.tracing.Backend;
import com.argus.tracing.internal.UnifiedSdkInitializer;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;

import java.lang.instrument.Instrumentation;

/**
 * Java agent entry point for transparent, zero-code-change distributed tracing.
 *
 * <h2>Usage</h2>
 * <pre>
 * java -javaagent:argus-tracing-agent.jar [options] -jar myapp.jar
 * </pre>
 *
 * <h2>Configuration — environment variables</h2>
 * <table border="1">
 *   <tr><th>Variable</th><th>Values</th><th>Default</th></tr>
 *   <tr>
 *     <td>{@code ARGUS_TRACER_BACKEND}</td>
 *     <td>{@code otel} | {@code datadog} | {@code noop}</td>
 *     <td>{@code noop} (agent does nothing when variable is absent)</td>
 *   </tr>
 *   <tr>
 *     <td>{@code OTEL_SERVICE_NAME}</td>
 *     <td>any string</td>
 *     <td>{@code unknown-service}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code OTEL_SERVICE_VERSION}</td>
 *     <td>any string</td>
 *     <td>{@code unknown}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code DEPLOYMENT_ENVIRONMENT}</td>
 *     <td>any string</td>
 *     <td>{@code development}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code OTEL_EXPORTER_OTLP_ENDPOINT}</td>
 *     <td>URL</td>
 *     <td>console (OTel backend only)</td>
 *   </tr>
 *   <tr>
 *     <td>{@code DD_AGENT_HOST}</td>
 *     <td>hostname / IP</td>
 *     <td>{@code localhost} (Datadog backend only)</td>
 *   </tr>
 * </table>
 *
 * <h2>OTel backend example</h2>
 * <pre>
 * ARGUS_TRACER_BACKEND=otel \
 * OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318 \
 * OTEL_SERVICE_NAME=order-service \
 * java -javaagent:argus-tracing-agent.jar -jar order-service.jar
 * </pre>
 *
 * <h2>Datadog backend example</h2>
 * <pre>
 * ARGUS_TRACER_BACKEND=datadog \
 * DD_AGENT_HOST=datadog-agent.svc.cluster.local \
 * OTEL_SERVICE_NAME=order-service \
 * java -javaagent:argus-tracing-agent.jar -jar order-service.jar
 * </pre>
 *
 * <p>The Datadog agent must have OTLP ingestion enabled on port 4318:</p>
 * <pre>
 * # datadog.yaml
 * otlp_config:
 *   receiver:
 *     protocols:
 *       http:
 *         endpoint: "0.0.0.0:4318"
 * </pre>
 *
 * <h2>How the application picks it up — zero code changes</h2>
 * <ol>
 *   <li>This agent's {@code premain()} runs before the application's {@code main()}.</li>
 *   <li>It builds an {@link OpenTelemetrySdk} (logging + tracing providers) and
 *       registers it as {@link GlobalOpenTelemetry}.</li>
 *   <li>It sets the system property {@code argus.agent.initialized=true}.</li>
 *   <li>{@code ArgusLoggerFactory.create()} detects that property and delegates to
 *       {@code GlobalOpenTelemetry.get()} instead of building a standalone SDK.</li>
 *   <li>Every subsequent {@code ArgusLogger.emit()} call automatically carries
 *       {@code trace_id} and {@code span_id} from whatever span is active at that
 *       moment — injected by the OTel SDK from the active context.</li>
 * </ol>
 */
public final class ArgusTracingAgent {

    private ArgusTracingAgent() {}

    /**
     * Called by the JVM before {@code main()} when the JAR is specified via
     * {@code -javaagent}.
     *
     * @param agentArgs command-line arguments after the JAR path (usually empty)
     * @param inst      JVM instrumentation handle (not used — we don't rewrite bytecode)
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        initialise();
    }

    /**
     * Called when the agent is attached to a running JVM (dynamic attach).
     * Same behaviour as {@link #premain}.
     */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        initialise();
    }

    // -------------------------------------------------------------------------

    private static void initialise() {
        Backend backend = resolveBackend();

        if (backend == Backend.NOOP) {
            // Nothing to do — logging still works standalone, just without trace context.
            log("backend=noop — tracing disabled");
            return;
        }

        try {
            OpenTelemetrySdk sdk = UnifiedSdkInitializer.initialize(
                    backend,
                    System.getenv("OTEL_SERVICE_NAME"),
                    System.getenv("OTEL_SERVICE_VERSION"),
                    resolveEnv("DEPLOYMENT_ENVIRONMENT", "APP_ENV", "development"),
                    System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT"),
                    System.getenv("DD_AGENT_HOST"));

            // Register as the global OTel instance so ArgusLoggerFactory.create()
            // picks it up via the "argus.agent.initialized" property check.
            GlobalOpenTelemetry.set(sdk);

            // Signal to ArgusLoggerFactory.create() that the global SDK is ready.
            System.setProperty("argus.agent.initialized", "true");

            log("backend=" + backend.name().toLowerCase()
                    + " sdk registered with GlobalOpenTelemetry");

            // Flush on normal JVM shutdown.
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log("shutting down");
                sdk.close();
            }, "argus-agent-shutdown"));

        } catch (Exception e) {
            // Never crash the application — log a warning and carry on without tracing.
            System.err.println("[argus-tracing-agent] initialisation failed: " + e.getMessage()
                    + " — continuing without tracing");
        }
    }

    // -------------------------------------------------------------------------

    private static Backend resolveBackend() {
        // System property takes precedence over env var (consistent with ArgusTracerFactory).
        String prop = System.getProperty("argus.tracer.backend");
        if (prop != null && !prop.isBlank()) return parseBackend(prop);

        String env = System.getenv("ARGUS_TRACER_BACKEND");
        if (env != null && !env.isBlank()) return parseBackend(env);

        // Default to NOOP when nothing is configured — opt-in only.
        return Backend.NOOP;
    }

    private static Backend parseBackend(String value) {
        return switch (value.trim().toUpperCase()) {
            case "OTEL", "OPENTELEMETRY" -> Backend.OTEL;
            case "DATADOG", "DD"          -> Backend.DATADOG;
            default                       -> Backend.NOOP;
        };
    }

    private static String resolveEnv(String primary, String fallback, String defaultValue) {
        String v = System.getenv(primary);
        if (v != null && !v.isBlank()) return v;
        v = System.getenv(fallback);
        return (v != null && !v.isBlank()) ? v : defaultValue;
    }

    private static void log(String msg) {
        System.out.println("[argus-tracing-agent] " + msg);
    }
}
