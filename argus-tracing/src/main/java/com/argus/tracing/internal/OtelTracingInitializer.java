package com.argus.tracing.internal;

import com.argus.tracing.TracerConfig;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

/**
 * Builds a standalone {@link SdkTracerProvider} for the OTel backend.
 *
 * <p>Mirrors the exporter-selection logic of the logging module's {@code OtelInitializer}:</p>
 * <ul>
 *   <li>{@code OTEL_EXPORTER_OTLP_ENDPOINT} set → {@link OtlpHttpSpanExporter} + batch processor.</li>
 *   <li>Not set → {@link ConsoleSpanExporter} + simple (immediate) processor for dev.</li>
 * </ul>
 */
final class OtelTracingInitializer {

    static final String INSTRUMENTATION_SCOPE   = "com.argus.tracing";
    static final String INSTRUMENTATION_VERSION = "1.0.0";

    private OtelTracingInitializer() {}

    /**
     * Build tracer handles from config.  The caller owns the returned
     * {@link SdkTracerProvider} and is responsible for shutting it down.
     */
    static OtelHandles initialize(TracerConfig config) {
        String name    = resolve(config.serviceName(),    "OTEL_SERVICE_NAME",    "unknown-service");
        String version = resolve(config.serviceVersion(), "OTEL_SERVICE_VERSION", "unknown");
        String env     = resolve(config.environment(),    "DEPLOYMENT_ENVIRONMENT",
                           resolve(null,               "APP_ENV",               "development"));

        String otlpEndpoint = config.exporterEndpoint() != null
                ? config.exporterEndpoint()
                : System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT");

        Resource resource = Resource.getDefault().toBuilder()
                .put(AttributeKey.stringKey("service.name"),                name)
                .put(AttributeKey.stringKey("service.version"),             version)
                .put(AttributeKey.stringKey("deployment.environment.name"), env)
                .build();

        SpanExporter exporter;
        boolean      batch;

        if (otlpEndpoint != null && !otlpEndpoint.isBlank()) {
            exporter = OtlpHttpSpanExporter.builder()
                    .setEndpoint(otlpEndpoint)
                    .build();
            batch = true;
        } else {
            exporter = ConsoleSpanExporter.create();
            batch = false;
        }

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setResource(resource)
                .addSpanProcessor(batch
                        ? BatchSpanProcessor.builder(exporter).build()
                        : SimpleSpanProcessor.create(exporter))
                .build();

        Tracer tracer = tracerProvider.tracerBuilder(INSTRUMENTATION_SCOPE)
                .setInstrumentationVersion(INSTRUMENTATION_VERSION)
                .build();

        return new OtelHandles(tracer, tracerProvider);
    }

    /** Holds the two objects the backend needs after initialisation. */
    record OtelHandles(Tracer tracer, SdkTracerProvider tracerProvider) {}

    // -------------------------------------------------------------------------

    private static String resolve(String explicit, String envKey, String fallback) {
        if (explicit != null && !explicit.isBlank()) return explicit;
        String env = System.getenv(envKey);
        return (env != null && !env.isBlank()) ? env : fallback;
    }
}
