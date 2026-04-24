package com.argus.tracing.internal;

import com.argus.logging.internal.OtelInitializer;
import com.argus.tracing.Backend;
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
 * Builds a standalone {@link SdkTracerProvider} for the OTel or Datadog backend.
 *
 * <p>Used by {@link OtelTracerBackend} when no external {@code OpenTelemetry} instance
 * is supplied.  When {@link com.argus.tracing.ArgusAgent} or the
 * {@code argus-tracing-agent} JAR is in use, they provide a pre-built SDK instead and
 * this initializer is bypassed.</p>
 *
 * <h2>Endpoint resolution</h2>
 * <ul>
 *   <li>{@link Backend#OTEL}: {@code TracerConfig.exporterEndpoint} →
 *       {@code OTEL_EXPORTER_OTLP_ENDPOINT} → console (dev)</li>
 *   <li>{@link Backend#DATADOG}: {@code TracerConfig.exporterEndpoint} →
 *       {@code http://${DD_AGENT_HOST:-localhost}:4318}
 *       (Datadog agent OTLP/HTTP ingestion port)</li>
 * </ul>
 */
final class OtelTracingInitializer {

    static final String INSTRUMENTATION_SCOPE   = "com.argus.tracing";
    static final String INSTRUMENTATION_VERSION = "1.0.0";

    private OtelTracingInitializer() {}

    static OtelHandles initialize(TracerConfig config) {
        String name    = OtelInitializer.resolve(config.serviceName(),    "OTEL_SERVICE_NAME",    "unknown-service");
        String version = OtelInitializer.resolve(config.serviceVersion(), "OTEL_SERVICE_VERSION", "unknown");
        String env     = OtelInitializer.resolve(config.environment(),    "DEPLOYMENT_ENVIRONMENT",
                           OtelInitializer.resolve(null,                  "APP_ENV",               "development"));

        String otlpEndpoint = resolveEndpoint(config);
        Resource resource   = buildResource(config.backend(), name, version, env);

        SpanExporter exporter;
        boolean      batch;

        if (otlpEndpoint != null && !otlpEndpoint.isBlank()) {
            exporter = OtlpHttpSpanExporter.builder().setEndpoint(otlpEndpoint).build();
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

        Tracer tracer = tracerProvider.tracerBuilder(INSTRUMENTATION_SCOPE)
                .setInstrumentationVersion(INSTRUMENTATION_VERSION)
                .build();

        return new OtelHandles(tracer, tracerProvider);
    }

    record OtelHandles(Tracer tracer, SdkTracerProvider tracerProvider) {}

    // -------------------------------------------------------------------------

    private static String resolveEndpoint(TracerConfig config) {
        if (config.exporterEndpoint() != null && !config.exporterEndpoint().isBlank()) {
            return config.exporterEndpoint();
        }
        if (config.backend() == Backend.DATADOG) {
            String host = OtelInitializer.resolve(null, "DD_AGENT_HOST", "localhost");
            return "http://" + host + ":4318";
        }
        return System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT");
    }

    private static Resource buildResource(Backend backend, String name, String version, String env) {
        Resource base = OtelInitializer.buildResource(name, version, env);
        if (backend != Backend.DATADOG) return base;
        // Datadog Unified Service Tagging — required for APM ↔ log correlation in Datadog
        return base.toBuilder()
                .put(AttributeKey.stringKey("dd.service"), name)
                .put(AttributeKey.stringKey("dd.env"),     env)
                .put(AttributeKey.stringKey("dd.version"), version)
                .build();
    }
}
