package com.argus.tracing.internal;

import com.argus.logging.internal.OtelInitializer;
import com.argus.tracing.Backend;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

/**
 * Builds a unified {@link OpenTelemetrySdk} that wires <em>both</em> the logging provider
 * and the tracing provider to the same Resource and exporter pipeline.
 *
 * <p>This is what {@link com.argus.tracing.ArgusAgent} uses internally.  Because
 * both providers share the same SDK instance, OTel context propagation ensures that
 * any {@code ArgusLogger.emit()} call made while an {@code ArgusTracer} span is active
 * automatically carries the matching {@code trace_id} / {@code span_id}.</p>
 *
 * <h2>Exporter resolution by backend</h2>
 * <table border="1">
 *   <tr><th>Backend</th><th>OTLP endpoint</th><th>Extra resource attrs</th></tr>
 *   <tr>
 *     <td>OTEL</td>
 *     <td>{@code explicit} → {@code OTEL_EXPORTER_OTLP_ENDPOINT} → console</td>
 *     <td>–</td>
 *   </tr>
 *   <tr>
 *     <td>DATADOG</td>
 *     <td>{@code explicit} → {@code http://${DD_AGENT_HOST:-localhost}:4318}
 *         (Datadog agent OTLP/HTTP ingestion)</td>
 *     <td>{@code dd.service}, {@code dd.env}, {@code dd.version}
 *         (Datadog Unified Service Tagging)</td>
 *   </tr>
 * </table>
 */
public final class UnifiedSdkInitializer {

    static final String INSTRUMENTATION_SCOPE   = "com.argus.tracing";
    static final String INSTRUMENTATION_VERSION = "1.0.0";

    private UnifiedSdkInitializer() {}

    /**
     * @param backend          already-resolved backend
     * @param serviceName      nullable — resolved against env vars
     * @param serviceVersion   nullable — resolved against env vars
     * @param environment      nullable — resolved against env vars
     * @param exporterEndpoint nullable — explicit OTLP endpoint override
     * @param datadogAgentHost nullable — hostname of the Datadog agent (DATADOG backend only)
     */
    public static OpenTelemetrySdk initialize(
            Backend backend,
            String  serviceName,
            String  serviceVersion,
            String  environment,
            String  exporterEndpoint,
            String  datadogAgentHost) {

        // ---- Resolve service metadata ----------------------------------------
        String name    = OtelInitializer.resolve(serviceName,    "OTEL_SERVICE_NAME",    "unknown-service");
        String version = OtelInitializer.resolve(serviceVersion, "OTEL_SERVICE_VERSION", "unknown");
        String env     = OtelInitializer.resolve(environment,    "DEPLOYMENT_ENVIRONMENT",
                           OtelInitializer.resolve(null,         "APP_ENV",               "development"));

        // ---- Resolve OTLP endpoint -------------------------------------------
        String otlpEndpoint = resolveEndpoint(backend, exporterEndpoint, datadogAgentHost);

        // ---- Build shared Resource -------------------------------------------
        Resource resource = buildResource(backend, name, version, env);

        // ---- Logging provider (reuses OtelInitializer logic + console exporter)
        SdkLoggerProvider loggerProvider = OtelInitializer.buildLoggerProvider(resource, otlpEndpoint);

        // ---- Tracing provider -----------------------------------------------
        SpanExporter spanExporter;
        boolean      batch;
        if (otlpEndpoint != null && !otlpEndpoint.isBlank()) {
            spanExporter = OtlpHttpSpanExporter.builder().setEndpoint(otlpEndpoint).build();
            batch        = true;
        } else {
            spanExporter = ConsoleSpanExporter.create();
            batch        = false;
        }

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setResource(resource)
                .addSpanProcessor(batch
                        ? BatchSpanProcessor.builder(spanExporter).build()
                        : SimpleSpanProcessor.create(spanExporter))
                .build();

        // ---- Combine into a single SDK instance -----------------------------
        return OpenTelemetrySdk.builder()
                .setLoggerProvider(loggerProvider)
                .setTracerProvider(tracerProvider)
                .build();
    }

    // -------------------------------------------------------------------------

    /**
     * Resolves the OTLP endpoint for logs and traces.
     *
     * <ul>
     *   <li>OTEL backend: {@code explicit} → {@code OTEL_EXPORTER_OTLP_ENDPOINT} → {@code null} (console)</li>
     *   <li>DATADOG backend: {@code explicit} → {@code http://<DD_AGENT_HOST>:4318}</li>
     * </ul>
     */
    private static String resolveEndpoint(Backend backend, String explicit, String ddHost) {
        if (explicit != null && !explicit.isBlank()) return explicit;

        if (backend == Backend.DATADOG) {
            String host = ddHost != null && !ddHost.isBlank() ? ddHost
                    : OtelInitializer.resolve(null, "DD_AGENT_HOST", "localhost");
            // Datadog agent OTLP/HTTP ingestion port — enabled in datadog.yaml under
            // otlp_config.receiver.protocols.http.endpoint
            return "http://" + host + ":4318";
        }

        // OTEL backend: standard env var, null → console
        return System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT");
    }

    /**
     * Builds a Resource with standard OTel service attributes plus, for the Datadog
     * backend, the Datadog Unified Service Tagging attributes ({@code dd.service},
     * {@code dd.env}, {@code dd.version}).
     */
    private static Resource buildResource(Backend backend, String name, String version, String env) {
        Resource base = OtelInitializer.buildResource(name, version, env);
        if (backend != Backend.DATADOG) return base;

        // Datadog Unified Service Tagging — required for APM ↔ log correlation in Datadog.
        // https://docs.datadoghq.com/getting_started/tagging/unified_service_tagging/
        return base.toBuilder()
                .put(AttributeKey.stringKey("dd.service"), name)
                .put(AttributeKey.stringKey("dd.env"),     env)
                .put(AttributeKey.stringKey("dd.version"), version)
                .build();
    }
}
