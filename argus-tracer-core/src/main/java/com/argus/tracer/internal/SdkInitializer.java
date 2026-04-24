package com.argus.tracer.internal;

import com.argus.tracer.Backend;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

/**
 * Builds an {@link OpenTelemetrySdk} configured for the requested backend.
 *
 * <h2>Endpoint resolution</h2>
 * <table border="1">
 *   <tr><th>Backend</th><th>Endpoint</th></tr>
 *   <tr><td>OTEL</td>
 *       <td>explicit → {@code OTEL_EXPORTER_OTLP_ENDPOINT} → JSON console (dev)</td></tr>
 *   <tr><td>DATADOG</td>
 *       <td>explicit → {@code http://$DD_AGENT_HOST:4318} (Datadog OTLP ingestion)</td></tr>
 * </table>
 *
 * <h2>Datadog Unified Service Tagging</h2>
 * <p>When backend is {@link Backend#DATADOG}, the resource automatically includes
 * {@code dd.service}, {@code dd.env}, and {@code dd.version} — required for APM ↔ log
 * correlation in Datadog.</p>
 */
public final class SdkInitializer {

    private SdkInitializer() {}

    /**
     * @param backend          resolved backend (never NOOP)
     * @param serviceName      nullable — resolved against {@code OTEL_SERVICE_NAME}
     * @param serviceVersion   nullable — resolved against {@code OTEL_SERVICE_VERSION}
     * @param environment      nullable — resolved against {@code DEPLOYMENT_ENVIRONMENT}
     * @param exporterEndpoint nullable — explicit OTLP endpoint override
     * @param datadogAgentHost nullable — Datadog agent hostname (DATADOG backend only)
     */
    public static OpenTelemetrySdk initialize(
            Backend backend,
            String  serviceName,
            String  serviceVersion,
            String  environment,
            String  exporterEndpoint,
            String  datadogAgentHost) {

        String name = resolve(serviceName,    "OTEL_SERVICE_NAME",     "unknown-service");
        String ver  = resolve(serviceVersion, "OTEL_SERVICE_VERSION",  "unknown");
        String env  = resolve(environment,    "DEPLOYMENT_ENVIRONMENT",
                        resolve(null,         "APP_ENV",               "development"));

        String   endpoint = resolveEndpoint(backend, exporterEndpoint, datadogAgentHost);
        Resource resource = buildResource(backend, name, ver, env);

        SpanExporter exporter;
        boolean      batch;
        if (endpoint != null && !endpoint.isBlank()) {
            exporter = OtlpHttpSpanExporter.builder().setEndpoint(endpoint).build();
            batch    = true;
        } else {
            exporter = ConsoleSpanExporter.create();
            batch    = false;
        }

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setResource(resource)
                .addSpanProcessor(batch
                        ? BatchSpanProcessor.builder(exporter).build()
                        : SimpleSpanProcessor.create(exporter))
                .build();

        return OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();
    }

    // -------------------------------------------------------------------------

    private static Resource buildResource(Backend backend, String name, String ver, String env) {
        var rb = Resource.getDefault().toBuilder()
                .put(AttributeKey.stringKey("service.name"),    name)
                .put(AttributeKey.stringKey("service.version"), ver)
                .put(AttributeKey.stringKey("deployment.environment.name"), env);

        if (backend == Backend.DATADOG) {
            rb.put(AttributeKey.stringKey("dd.service"), name)
              .put(AttributeKey.stringKey("dd.env"),     env)
              .put(AttributeKey.stringKey("dd.version"), ver);
        }

        return rb.build();
    }

    private static String resolveEndpoint(Backend backend, String explicit, String ddHost) {
        if (explicit != null && !explicit.isBlank()) return explicit;

        if (backend == Backend.DATADOG) {
            String host = ddHost != null && !ddHost.isBlank() ? ddHost
                        : resolve(null, "DD_AGENT_HOST", "localhost");
            return "http://" + host + ":4318";
        }

        return System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT");
    }

    /** Resolve a value: explicit → env var → fallback. */
    public static String resolve(String explicit, String envKey, String fallback) {
        if (explicit != null && !explicit.isBlank()) return explicit;
        String v = System.getenv(envKey);
        return (v != null && !v.isBlank()) ? v : fallback;
    }
}
