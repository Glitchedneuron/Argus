package com.argus.logging.internal;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.resources.Resource;

/**
 * Builds a minimal, logs-only OTel SDK instance for use when the application does not
 * already manage its own OTel SDK.
 *
 * <h2>Exporter selection (checked in order)</h2>
 * <ol>
 *   <li>{@code OTEL_EXPORTER_OTLP_ENDPOINT} set → OTLP/HTTP log exporter, batched.</li>
 *   <li>Not set → {@link ConsoleLogRecordExporter}, unbatched (immediate, for dev).</li>
 * </ol>
 *
 * <h2>Resource attributes</h2>
 * <table>
 *   <tr><th>OTel attribute</th><th>Resolution order</th></tr>
 *   <tr><td>service.name</td><td>arg → OTEL_SERVICE_NAME → "unknown-service"</td></tr>
 *   <tr><td>service.version</td><td>arg → OTEL_SERVICE_VERSION → "unknown"</td></tr>
 *   <tr><td>deployment.environment.name</td><td>arg → DEPLOYMENT_ENVIRONMENT → APP_ENV → "development"</td></tr>
 * </table>
 */
public final class OtelInitializer {

    private OtelInitializer() {}

    /**
     * Initialise a standalone OTel SDK that emits logs only.
     * Arguments take precedence over environment variables.
     *
     * @param serviceName    nullable — falls back to env / default
     * @param serviceVersion nullable — falls back to env / default
     * @param environment    nullable — falls back to env / default
     */
    public static OpenTelemetry initialize(
            String serviceName,
            String serviceVersion,
            String environment) {

        String name    = resolve(serviceName,    "OTEL_SERVICE_NAME",    "unknown-service");
        String version = resolve(serviceVersion, "OTEL_SERVICE_VERSION", "unknown");
        String env     = resolve(environment,    "DEPLOYMENT_ENVIRONMENT",
                           resolve(null,         "APP_ENV",               "development"));

        Resource          resource       = buildResource(name, version, env);
        SdkLoggerProvider loggerProvider = buildLoggerProvider(
                resource, System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT"));

        return OpenTelemetrySdk.builder()
                .setLoggerProvider(loggerProvider)
                .build();
    }

    // -------------------------------------------------------------------------
    // Building-block helpers — used internally and by argus-tracing's unified SDK builder.
    // -------------------------------------------------------------------------

    /**
     * Build an OTel Resource with the three standard Argus attributes.
     * Arguments are already-resolved (non-null) values.
     */
    public static Resource buildResource(String serviceName, String serviceVersion, String environment) {
        return Resource.getDefault().toBuilder()
                .put(AttributeKey.stringKey("service.name"),                serviceName)
                .put(AttributeKey.stringKey("service.version"),             serviceVersion)
                .put(AttributeKey.stringKey("deployment.environment.name"), environment)
                .build();
    }

    /**
     * Build a {@link SdkLoggerProvider} for the given resource and OTLP endpoint.
     *
     * @param resource     pre-built resource (call {@link #buildResource} first)
     * @param otlpEndpoint nullable — {@code null} selects the console exporter
     */
    public static SdkLoggerProvider buildLoggerProvider(Resource resource, String otlpEndpoint) {
        LogRecordExporter exporter;
        boolean           batch;
        if (otlpEndpoint != null && !otlpEndpoint.isBlank()) {
            exporter = OtlpHttpLogRecordExporter.builder().setEndpoint(otlpEndpoint).build();
            batch    = true;
        } else {
            exporter = ConsoleLogRecordExporter.create();
            batch    = false;
        }
        return SdkLoggerProvider.builder()
                .setResource(resource)
                .addLogRecordProcessor(batch
                        ? BatchLogRecordProcessor.builder(exporter).build()
                        : SimpleLogRecordProcessor.create(exporter))
                .build();
    }

    /** Returns the first non-blank value among: explicit arg → env var → hardcoded default. */
    public static String resolve(String explicit, String envKey, String fallback) {
        if (explicit != null && !explicit.isBlank()) return explicit;
        String env = System.getenv(envKey);
        return (env != null && !env.isBlank()) ? env : fallback;
    }
}
