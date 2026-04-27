package com.argus.tracer.agent;

import com.argus.tracer.Backend;
import com.argus.tracer.internal.SdkInitializer;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;

import java.lang.instrument.Instrumentation;

/**
 * Java agent entry point for transparent, zero-code-change distributed tracing.
 *
 * <h2>Usage</h2>
 * <pre>
 * java -javaagent:argus-tracer-agent.jar [options] -jar myapp.jar
 * </pre>
 *
 * <h2>How it works</h2>
 * <ol>
 *   <li>{@link #premain} runs before the application's {@code main()}.</li>
 *   <li>It reads {@code ARGUS_TRACER_BACKEND} to select the backend.</li>
 *   <li>It calls {@link SdkInitializer#initialize} to build an {@link OpenTelemetrySdk}.</li>
 *   <li>The SDK is registered as {@link GlobalOpenTelemetry} — any OTel-aware framework
 *       or library (including {@code ArgusTracerFactory.create()}) will use it.</li>
 *   <li>The system property {@code argus.tracer.initialized=true} is set so
 *       {@code ArgusTracerFactory.create()} knows to wrap GlobalOpenTelemetry.</li>
 * </ol>
 *
 * <h2>Configuration — environment variables</h2>
 * <table border="1">
 *   <tr><th>Variable</th><th>Values</th><th>Default</th></tr>
 *   <tr><td>{@code ARGUS_TRACER_BACKEND}</td>
 *       <td>{@code otel} | {@code datadog} | {@code noop}</td>
 *       <td>{@code noop} — agent is inert when the variable is absent</td></tr>
 *   <tr><td>{@code OTEL_SERVICE_NAME}</td><td>any string</td><td>{@code unknown-service}</td></tr>
 *   <tr><td>{@code OTEL_SERVICE_VERSION}</td><td>any string</td><td>{@code unknown}</td></tr>
 *   <tr><td>{@code DEPLOYMENT_ENVIRONMENT}</td><td>any string</td><td>{@code development}</td></tr>
 *   <tr><td>{@code OTEL_EXPORTER_OTLP_ENDPOINT}</td><td>URL</td>
 *       <td>JSON console (OTel backend only)</td></tr>
 *   <tr><td>{@code DD_AGENT_HOST}</td><td>hostname / IP</td>
 *       <td>{@code localhost} (Datadog backend only)</td></tr>
 * </table>
 *
 * <h2>OTel backend example</h2>
 * <pre>
 * ARGUS_TRACER_BACKEND=otel \
 * OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318 \
 * OTEL_SERVICE_NAME=order-service \
 * java -javaagent:argus-tracer-agent.jar -jar order-service.jar
 * </pre>
 *
 * <h2>Datadog backend example</h2>
 * <pre>
 * ARGUS_TRACER_BACKEND=datadog \
 * DD_AGENT_HOST=datadog-agent.svc.cluster.local \
 * OTEL_SERVICE_NAME=order-service \
 * java -javaagent:argus-tracer-agent.jar -jar order-service.jar
 * </pre>
 *
 * <p>The Datadog agent must have OTLP ingestion enabled on port 4318 (see README).</p>
 */
public final class ArgusTracerAgent {

    private ArgusTracerAgent() {}

    /** Called by the JVM before {@code main()} when attached via {@code -javaagent}. */
    public static void premain(String agentArgs, Instrumentation inst) {
        initialise();
    }

    /** Called when attached to a running JVM via dynamic attach. Same behaviour as {@link #premain}. */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        initialise();
    }

    // -------------------------------------------------------------------------

    private static void initialise() {
        Backend backend = resolveBackend();

        if (backend == Backend.NOOP) {
            log("backend=noop — tracing disabled (set ARGUS_TRACER_BACKEND to enable)");
            return;
        }

        try {
            OpenTelemetrySdk sdk = SdkInitializer.initialize(
                    backend,
                    System.getenv("OTEL_SERVICE_NAME"),
                    System.getenv("OTEL_SERVICE_VERSION"),
                    resolveEnv("DEPLOYMENT_ENVIRONMENT", "APP_ENV", "development"),
                    System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT"),
                    System.getenv("DD_AGENT_HOST"));

            // Register as the global OTel instance — picked up by ArgusTracerFactory.create()
            // and any other OTel-aware framework on the classpath.
            GlobalOpenTelemetry.set(sdk);

            // Signal to ArgusTracerFactory.create() that the global SDK is ready.
            System.setProperty("argus.tracer.initialized", "true");

            log("backend=" + backend.name().toLowerCase() + " registered with GlobalOpenTelemetry");

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log("flushing spans and shutting down");
                sdk.getSdkTracerProvider().forceFlush();
                sdk.close();
            }, "argus-tracer-shutdown"));

        } catch (Exception e) {
            // Never crash the application — warn and continue without tracing.
            System.err.println("[argus-tracer-agent] initialisation failed: " + e.getMessage()
                    + " — continuing without tracing");
        }
    }

    // -------------------------------------------------------------------------

    private static Backend resolveBackend() {
        String prop = System.getProperty("argus.tracer.backend");
        if (prop != null && !prop.isBlank()) return parseBackend(prop);

        String env = System.getenv("ARGUS_TRACER_BACKEND");
        if (env != null && !env.isBlank()) return parseBackend(env);

        return Backend.NOOP;
    }

    private static Backend parseBackend(String value) {
        return switch (value.trim().toUpperCase()) {
            case "OTEL", "OPENTELEMETRY" -> Backend.OTEL;
            case "DATADOG", "DD"         -> Backend.DATADOG;
            default                      -> Backend.NOOP;
        };
    }

    private static String resolveEnv(String primary, String fallback, String defaultValue) {
        String val = System.getenv(primary);
        if (val != null && !val.isBlank()) return val;
        val = System.getenv(fallback);
        return (val != null && !val.isBlank()) ? val : defaultValue;
    }

    private static void log(String msg) {
        System.out.println("[argus-tracer-agent] " + msg);
    }
}
